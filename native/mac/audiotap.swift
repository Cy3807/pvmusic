// pvm-audiotap: 抓取 macOS 系统正在播放的声音（排除指定进程，如 Minecraft 自己），
// 以 s16le 立体声写到 stdout。需要 macOS 14.2+（Core Audio 进程 tap）。
// 用法: pvm-audiotap [--include 名字或包名片段]... <要排除的进程号>...   (有 --include 就只抓匹配的软件)
//       pvm-audiotap --list   列出所有音频进程: 包名<TAB>名字<TAB>是否正在出声
// 输出: 先 12 字节头 "HPA1" + UInt32 采样率 + UInt32 声道数(=2)，之后是连续的 PCM。
import CoreAudio
import AudioToolbox
import Foundation
import AppKit

func fail(_ msg: String, _ code: Int32 = 2) -> Never {
    FileHandle.standardError.write((msg + "\n").data(using: .utf8)!)
    exit(code)
}

func addr(_ sel: AudioObjectPropertySelector) -> AudioObjectPropertyAddress {
    AudioObjectPropertyAddress(mSelector: sel, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
}

func processObject(_ pid: pid_t) -> AudioObjectID? {
    var a = addr(kAudioHardwarePropertyTranslatePIDToProcessObject)
    var p = pid
    var obj = AudioObjectID(kAudioObjectUnknown)
    var size = UInt32(MemoryLayout<AudioObjectID>.size)
    let st = AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &a,
                                        UInt32(MemoryLayout<pid_t>.size), &p, &size, &obj)
    return (st == noErr && obj != AudioObjectID(kAudioObjectUnknown)) ? obj : nil
}

func defaultOutputUID() -> String? {
    var a = addr(kAudioHardwarePropertyDefaultSystemOutputDevice)
    var dev = AudioObjectID(kAudioObjectUnknown)
    var size = UInt32(MemoryLayout<AudioObjectID>.size)
    guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &a, 0, nil, &size, &dev) == noErr else { return nil }
    var ua = addr(kAudioDevicePropertyDeviceUID)
    var uid: Unmanaged<CFString>?
    var usize = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
    guard AudioObjectGetPropertyData(dev, &ua, 0, nil, &usize, &uid) == noErr, let u = uid else { return nil }
    return u.takeRetainedValue() as String
}

func processList() -> [(obj: AudioObjectID, pid: pid_t, bundle: String, name: String, running: Bool)] {
    var a = addr(kAudioHardwarePropertyProcessObjectList)
    var size: UInt32 = 0
    guard AudioObjectGetPropertyDataSize(AudioObjectID(kAudioObjectSystemObject), &a, 0, nil, &size) == noErr else { return [] }
    var ids = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
    guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &a, 0, nil, &size, &ids) == noErr else { return [] }
    return ids.compactMap { obj in
        var pa = addr(kAudioProcessPropertyPID)
        var pid: pid_t = 0
        var ps = UInt32(MemoryLayout<pid_t>.size)
        guard AudioObjectGetPropertyData(obj, &pa, 0, nil, &ps, &pid) == noErr else { return nil }
        var ba = addr(kAudioProcessPropertyBundleID)
        var bid: Unmanaged<CFString>?
        var bs = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        var bundle = ""
        if AudioObjectGetPropertyData(obj, &ba, 0, nil, &bs, &bid) == noErr, let b = bid { bundle = b.takeRetainedValue() as String }
        var ra = addr(kAudioProcessPropertyIsRunningOutput)
        var run: UInt32 = 0
        var rs = UInt32(MemoryLayout<UInt32>.size)
        _ = AudioObjectGetPropertyData(obj, &ra, 0, nil, &rs, &run)
        let name = NSRunningApplication(processIdentifier: pid)?.localizedName ?? ""
        return (obj, pid, bundle, name, run != 0)
    }
}

var includeTokens: [String] = []
var excludePids: [pid_t] = []
var listMode = false
var argv = Array(CommandLine.arguments.dropFirst())
while !argv.isEmpty {
    let a = argv.removeFirst()
    if a == "--list" { listMode = true }
    else if a == "--include", !argv.isEmpty { includeTokens.append(argv.removeFirst().lowercased()) }
    else if let p = Int32(a) { excludePids.append(p) }
}

if listMode {
    for p in processList() where !p.bundle.isEmpty || !p.name.isEmpty {
        print("\(p.bundle)\t\(p.name)\t\(p.running ? 1 : 0)")
    }
    exit(0)
}

let excluded: [AudioObjectID] = excludePids.compactMap { processObject($0) }

let desc: CATapDescription
if includeTokens.isEmpty {
    desc = CATapDescription(stereoGlobalTapButExcludeProcesses: excluded)
} else {
    let matches = processList().filter { p in
        !excludePids.contains(p.pid) && includeTokens.contains { t in
            p.bundle.lowercased().contains(t) || p.name.lowercased().contains(t)
        }
    }.map { $0.obj }
    if matches.isEmpty { fail("no matching app", 3) }
    desc = CATapDescription(stereoMixdownOfProcesses: matches)
}
desc.name = "Plasmo Voice Music tap"
desc.uuid = UUID()
desc.muteBehavior = .unmuted
desc.isPrivate = true

var tapID = AudioObjectID(kAudioObjectUnknown)
var st = AudioHardwareCreateProcessTap(desc, &tapID)
if st != noErr { fail("create tap failed: \(st)") }

var aggDict: [String: Any] = [
    kAudioAggregateDeviceNameKey: "Plasmo Voice Music tap",
    kAudioAggregateDeviceUIDKey: UUID().uuidString,
    kAudioAggregateDeviceIsPrivateKey: true,
    kAudioAggregateDeviceTapAutoStartKey: true,
    kAudioAggregateDeviceTapListKey: [[
        kAudioSubTapUIDKey: desc.uuid.uuidString,
        kAudioSubTapDriftCompensationKey: true,
    ]],
]
if let uid = defaultOutputUID() {
    aggDict[kAudioAggregateDeviceMainSubDeviceKey] = uid
    aggDict[kAudioAggregateDeviceSubDeviceListKey] = [[kAudioSubDeviceUIDKey: uid]]
}

var aggID = AudioObjectID(kAudioObjectUnknown)
st = AudioHardwareCreateAggregateDevice(aggDict as CFDictionary, &aggID)
if st != noErr { fail("create aggregate device failed: \(st)") }

var fa = AudioObjectPropertyAddress(mSelector: kAudioTapPropertyFormat, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
var asbd = AudioStreamBasicDescription()
var asize = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
st = AudioObjectGetPropertyData(tapID, &fa, 0, nil, &asize, &asbd)
if st != noErr { fail("read tap format failed: \(st)") }

let rate = UInt32(asbd.mSampleRate)
let channels = max(1, Int(asbd.mChannelsPerFrame))
let isFloat = (asbd.mFormatFlags & kAudioFormatFlagIsFloat) != 0
let nonInterleaved = (asbd.mFormatFlags & kAudioFormatFlagIsNonInterleaved) != 0

func writeAll(_ p: UnsafeRawPointer, _ n: Int) {
    var off = 0
    while off < n {
        let w = write(1, p + off, n - off)
        if w <= 0 { exit(0) } // 父进程关掉管道 = 退出
        off += w
    }
}

var header = [UInt8]("HPA1".utf8)
var r = rate.littleEndian, c = UInt32(2).littleEndian
withUnsafeBytes(of: &r) { header.append(contentsOf: $0) }
withUnsafeBytes(of: &c) { header.append(contentsOf: $0) }
header.withUnsafeBytes { writeAll($0.baseAddress!, header.count) }

@inline(__always) func toS16(_ v: Float) -> Int16 {
    Int16(max(-1.0, min(1.0, v)) * 32767.0)
}

let queue = DispatchQueue(label: "pvm.audio", qos: .userInteractive)
var procID: AudioDeviceIOProcID?
st = AudioDeviceCreateIOProcIDWithBlock(&procID, aggID, queue) { _, inData, _, _, _ in
    let abl = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: inData))
    guard abl.count > 0 else { return }
    var frames = 0
    if nonInterleaved { frames = Int(abl[0].mDataByteSize) / (isFloat ? 4 : 2) }
    else { frames = Int(abl[0].mDataByteSize) / ((isFloat ? 4 : 2) * channels) }
    if frames <= 0 { return }
    var out = [Int16](repeating: 0, count: frames * 2)

    func sample(_ ch: Int, _ i: Int) -> Int16 {
        let chn = min(ch, channels - 1)
        if nonInterleaved {
            guard chn < abl.count, let d = abl[chn].mData else { return 0 }
            return isFloat ? toS16(d.assumingMemoryBound(to: Float.self)[i]) : d.assumingMemoryBound(to: Int16.self)[i]
        }
        guard let d = abl[0].mData else { return 0 }
        return isFloat ? toS16(d.assumingMemoryBound(to: Float.self)[i * channels + chn])
                       : d.assumingMemoryBound(to: Int16.self)[i * channels + chn]
    }
    for i in 0..<frames {
        out[i * 2] = sample(0, i)
        out[i * 2 + 1] = sample(1, i)
    }
    out.withUnsafeBytes { writeAll($0.baseAddress!, frames * 4) }
}
if st != noErr { fail("create IOProc failed: \(st)") }
st = AudioDeviceStart(aggID, procID)
if st != noErr { fail("start failed: \(st)") }

// 父进程（游戏）退出了就跟着退出
let t = DispatchSource.makeTimerSource(queue: .main)
let ppid = getppid()
t.schedule(deadline: .now() + 2, repeating: 2)
t.setEventHandler { if getppid() != ppid { exit(0) } }
t.resume()
dispatchMain()
