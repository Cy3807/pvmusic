# pvm-audiotap (Windows): 用 WASAPI 抓系统正在播放的声音，输出到 stdout。
#   默认: 进程回环（Windows 10 2004+/11）抓“除了游戏以外”的所有声音；-Include 名字,名字: 只抓这个软件（及它的子进程）
#   进程回环不可用时退回“默认输出设备回环”（会把游戏自己的声音也抓进去）
# 输出: 12 字节头 "HPA1" + UInt32 采样率 + UInt32 声道数(=2)，之后是连续的 s16le 立体声 PCM。
# -List: 列出有窗口的软件（名字<TAB>窗口标题<TAB>1）
param([string]$Include = "", [int]$ExcludePid = 0, [switch]$List)
$ErrorActionPreference = 'Stop'
$code = @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

public static class HpTap
{
    [ComImport, Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IActivateAudioInterfaceAsyncOperation
    {
        [PreserveSig] int GetActivateResult(out int activateResult, [MarshalAs(UnmanagedType.IUnknown)] out object activatedInterface);
    }

    [ComImport, Guid("41D949AB-9862-444A-80F6-C261334DA5EB"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IActivateAudioInterfaceCompletionHandler
    {
        [PreserveSig] int ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation);
    }

    [ComImport, Guid("94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAgileObject { }

    [ComImport, Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioClient
    {
        [PreserveSig] int Initialize(int shareMode, int streamFlags, long hnsBufferDuration, long hnsPeriodicity, IntPtr pFormat, IntPtr audioSessionGuid);
        [PreserveSig] int GetBufferSize(out uint bufferFrames);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out uint padding);
        [PreserveSig] int IsFormatSupported(int shareMode, IntPtr pFormat, out IntPtr closest);
        [PreserveSig] int GetMixFormat(out IntPtr pFormat);
        [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(IntPtr eventHandle);
        [PreserveSig] int GetService(ref Guid riid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [ComImport, Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioCaptureClient
    {
        [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
        [PreserveSig] int ReleaseBuffer(uint frames);
        [PreserveSig] int GetNextPacketSize(out uint frames);
    }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    class MMDeviceEnumeratorCom { }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
    }

    [DllImport("Mmdevapi.dll", ExactSpelling = true, PreserveSig = true)]
    static extern int ActivateAudioInterfaceAsync(
        [MarshalAs(UnmanagedType.LPWStr)] string deviceInterfacePath,
        ref Guid riid,
        IntPtr activationParams,
        IActivateAudioInterfaceCompletionHandler completionHandler,
        out IActivateAudioInterfaceAsyncOperation activationOperation);

    class Handler : IActivateAudioInterfaceCompletionHandler, IAgileObject
    {
        public ManualResetEvent Done = new ManualResetEvent(false);
        public int Result = -1;
        public object Client;
        public int ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation)
        {
            int hr; object obj;
            int r = operation.GetActivateResult(out hr, out obj);
            Result = r < 0 ? r : hr;
            Client = obj;
            Done.Set();
            return 0;
        }
    }

    static Guid IID_IAudioClient = new Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2");
    static Guid IID_IAudioCaptureClient = new Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317");

    // 进程回环: mode 0 = 只抓这个进程树, 1 = 抓除了这个进程树以外的所有声音
    static IAudioClient ActivateProcessLoopback(int pid, int mode)
    {
        IntPtr paramsPtr = Marshal.AllocHGlobal(12);
        Marshal.WriteInt32(paramsPtr, 0, 1);      // AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK
        Marshal.WriteInt32(paramsPtr, 4, pid);    // TargetProcessId
        Marshal.WriteInt32(paramsPtr, 8, mode);   // ProcessLoopbackMode

        int pvSize = IntPtr.Size == 8 ? 24 : 16;
        IntPtr pv = Marshal.AllocHGlobal(pvSize);
        for (int i = 0; i < pvSize; i++) Marshal.WriteByte(pv, i, 0);
        Marshal.WriteInt16(pv, 0, 65);            // VT_BLOB
        Marshal.WriteInt32(pv, 8, 12);            // BLOB.cbSize
        Marshal.WriteIntPtr(pv, IntPtr.Size == 8 ? 16 : 12, paramsPtr); // BLOB.pBlobData

        Handler handler = new Handler();
        IActivateAudioInterfaceAsyncOperation op;
        int hr = ActivateAudioInterfaceAsync("VAD\\Process_Loopback", ref IID_IAudioClient, pv, handler, out op);
        if (hr < 0) throw new Exception("ActivateAudioInterfaceAsync 0x" + hr.ToString("X8"));
        if (!handler.Done.WaitOne(5000)) throw new Exception("activation timeout");
        if (handler.Result < 0) throw new Exception("activation failed 0x" + handler.Result.ToString("X8"));
        return (IAudioClient)handler.Client;
    }

    static IntPtr PcmFormat48k()
    {
        IntPtr f = Marshal.AllocHGlobal(18);
        Marshal.WriteInt16(f, 0, 1);        // WAVE_FORMAT_PCM
        Marshal.WriteInt16(f, 2, 2);        // channels
        Marshal.WriteInt32(f, 4, 48000);    // sample rate
        Marshal.WriteInt32(f, 8, 192000);   // bytes per second
        Marshal.WriteInt16(f, 12, 4);       // block align
        Marshal.WriteInt16(f, 14, 16);      // bits
        Marshal.WriteInt16(f, 16, 0);       // cbSize
        return f;
    }

    static int FindPid(string include, int excludePid)
    {
        string[] tokens = include.ToLowerInvariant().Split(new char[] { ',' }, StringSplitOptions.RemoveEmptyEntries);
        int best = 0;
        foreach (Process p in Process.GetProcesses())
        {
            try
            {
                if (p.Id == excludePid) continue;
                string name = p.ProcessName.ToLowerInvariant();
                string title = (p.MainWindowTitle ?? "").ToLowerInvariant();
                foreach (string t0 in tokens)
                {
                    string t = t0.Trim();
                    if (t.Length == 0) continue;
                    if (name.Contains(t) || title.Contains(t))
                    {
                        if (p.MainWindowHandle != IntPtr.Zero) return p.Id;
                        if (best == 0) best = p.Id;
                    }
                }
            }
            catch (Exception) { }
        }
        return best;
    }

    static int ListApps()
    {
        Dictionary<string, bool> seen = new Dictionary<string, bool>();
        foreach (Process p in Process.GetProcesses())
        {
            try
            {
                string title = p.MainWindowTitle;
                if (string.IsNullOrEmpty(title)) continue;
                string line = p.ProcessName + "\t" + title.Replace('\t', ' ') + "\t1";
                if (!seen.ContainsKey(line)) { seen[line] = true; Console.Out.WriteLine(line); }
            }
            catch (Exception) { }
        }
        Console.Out.Flush();
        return 0;
    }

    static void WriteHeader(Stream o, int rate)
    {
        byte[] h = new byte[12];
        h[0] = (byte)'H'; h[1] = (byte)'P'; h[2] = (byte)'A'; h[3] = (byte)'1';
        BitConverter.GetBytes(rate).CopyTo(h, 4);
        BitConverter.GetBytes(2).CopyTo(h, 8);
        o.Write(h, 0, 12);
        o.Flush();
    }

    public static int Run(string include, int excludePid, bool list)
    {
        try
        {
            if (list) return ListApps();

            Stream stdout = Console.OpenStandardOutput();
            int target = excludePid, mode = 1;
            if (!string.IsNullOrEmpty(include))
            {
                target = FindPid(include, excludePid);
                if (target == 0) { Console.Error.WriteLine("no matching app"); return 3; }
                mode = 0;
            }

            IAudioClient client = null;
            IntPtr fmt = IntPtr.Zero;
            int rate = 48000;
            int srcChannels = 2;
            bool srcFloat = false;
            bool useEvent = false;
            AutoResetEvent ev = new AutoResetEvent(false);

            try
            {
                client = ActivateProcessLoopback(target, mode);
                fmt = PcmFormat48k();
                // LOOPBACK | EVENTCALLBACK | AUTOCONVERTPCM | SRC_DEFAULT_QUALITY
                int hr = client.Initialize(0, 0x00020000 | 0x00040000 | unchecked((int)0x80000000) | 0x08000000, 0, 0, fmt, IntPtr.Zero);
                if (hr < 0) throw new Exception("Initialize 0x" + hr.ToString("X8"));
                client.SetEventHandle(ev.SafeWaitHandle.DangerousGetHandle());
                useEvent = true;
            }
            catch (Exception e)
            {
                Console.Error.WriteLine("process loopback unavailable: " + e.Message);
                if (mode == 0) return 2; // 指定软件: 没法退回“全部”
                // 退回: 默认输出设备回环（连游戏自己的声音一起抓）
                IMMDeviceEnumerator en = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
                IMMDevice dev;
                int hr = en.GetDefaultAudioEndpoint(0, 0, out dev);
                if (hr < 0) throw new Exception("GetDefaultAudioEndpoint 0x" + hr.ToString("X8"));
                object o;
                hr = dev.Activate(ref IID_IAudioClient, 23, IntPtr.Zero, out o);
                if (hr < 0) throw new Exception("Activate 0x" + hr.ToString("X8"));
                client = (IAudioClient)o;
                client.GetMixFormat(out fmt);
                int tag = Marshal.ReadInt16(fmt, 0) & 0xFFFF;
                srcChannels = Marshal.ReadInt16(fmt, 2);
                rate = Marshal.ReadInt32(fmt, 4);
                int bits = Marshal.ReadInt16(fmt, 14);
                if (tag == 3) srcFloat = true;
                else if (tag == 0xFFFE) srcFloat = Marshal.ReadInt32(fmt, 24) == 3;
                if (!(srcFloat && bits == 32) && bits != 16) throw new Exception("unsupported mix format " + tag + "/" + bits);
                hr = client.Initialize(0, 0x00020000, 2000000, 0, fmt, IntPtr.Zero);
                if (hr < 0) throw new Exception("Initialize(loopback) 0x" + hr.ToString("X8"));
            }

            object capObj;
            int g = client.GetService(ref IID_IAudioCaptureClient, out capObj);
            if (g < 0) throw new Exception("GetService 0x" + g.ToString("X8"));
            IAudioCaptureClient cap = (IAudioCaptureClient)capObj;

            WriteHeader(stdout, rate);
            int s = client.Start();
            if (s < 0) throw new Exception("Start 0x" + s.ToString("X8"));

            Process parent = null;
            try { if (excludePid != 0) parent = Process.GetProcessById(excludePid); } catch (Exception) { }

            byte[] outBuf = new byte[0];
            DateTime lastCheck = DateTime.UtcNow;
            while (true)
            {
                if (useEvent) ev.WaitOne(20); else Thread.Sleep(10);
                uint frames;
                while (cap.GetNextPacketSize(out frames) >= 0 && frames > 0)
                {
                    IntPtr data; uint n; uint flags; ulong dp, qp;
                    if (cap.GetBuffer(out data, out n, out flags, out dp, out qp) < 0) break;
                    int count = (int)n;
                    if (outBuf.Length < count * 4) outBuf = new byte[count * 4];
                    if ((flags & 2) != 0)
                    {
                        Array.Clear(outBuf, 0, count * 4);
                    }
                    else if (useEvent)
                    {
                        Marshal.Copy(data, outBuf, 0, count * 4); // 已经是 48k/16/2
                    }
                    else
                    {
                        for (int i = 0; i < count; i++)
                        {
                            for (int c = 0; c < 2; c++)
                            {
                                int ch = c < srcChannels ? c : 0;
                                short v;
                                if (srcFloat)
                                {
                                    int bits = Marshal.ReadInt32(data, (i * srcChannels + ch) * 4);
                                    float f = BitConverter.ToSingle(BitConverter.GetBytes(bits), 0);
                                    if (f > 1f) f = 1f; else if (f < -1f) f = -1f;
                                    v = (short)(f * 32767f);
                                }
                                else v = Marshal.ReadInt16(data, (i * srcChannels + ch) * 2);
                                outBuf[i * 4 + c * 2] = (byte)(v & 0xFF);
                                outBuf[i * 4 + c * 2 + 1] = (byte)((v >> 8) & 0xFF);
                            }
                        }
                    }
                    cap.ReleaseBuffer(n);
                    stdout.Write(outBuf, 0, count * 4);
                }
                stdout.Flush();
                if ((DateTime.UtcNow - lastCheck).TotalSeconds > 2)
                {
                    lastCheck = DateTime.UtcNow;
                    if (parent != null && parent.HasExited) return 0; // 游戏退出了就跟着退出
                }
            }
        }
        catch (IOException) { return 0; } // 管道关了 = 游戏那边不要了
        catch (Exception e)
        {
            Console.Error.WriteLine(e.ToString());
            return 2;
        }
    }
}
'@
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }
Add-Type -TypeDefinition $code -Language CSharp
exit ([HpTap]::Run($Include, $ExcludePid, $List.IsPresent))
