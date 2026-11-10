package cy.pvmusic;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** 播放 config/pvmusic/music/ 里的音频文件（wav/aiff/au、ogg、mp3），解码后进自己的缓冲，和系统声音一起混。 */
public final class FilePlayer {
    private final PcmRing ring = new PcmRing(false);
    private final LocalMonitor monitor;
    public FilePlayer(LocalMonitor monitor) { this.monitor = monitor; }
    private final Resampler resampler = new Resampler();
    private volatile Thread thread;
    private volatile int targetRate = 48000;
    private volatile String playing = "";

    public static Path dir() {
        Path d = FabricLoader.getInstance().getConfigDir().resolve("pvmusic").resolve("music");
        try { Files.createDirectories(d); } catch (Exception ignored) { }
        return d;
    }

    public static List<String> files() {
        try (Stream<Path> s = Files.list(dir())) {
            return s.filter(Files::isRegularFile).filter(FilePlayer::supportedFile)
                    .map(p -> p.getFileName().toString()).sorted().toList();
        } catch (Exception e) { return List.of(); }
    }

    private static boolean supportedFile(Path path) {
        String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".wav") || n.endsWith(".aiff") || n.endsWith(".au") || n.endsWith(".ogg") || n.endsWith(".mp3") || n.endsWith(".flac") || n.endsWith(".m4a");
    }

    public boolean playing() { return thread != null; }
    public String name() { return playing; }
    public short[] read(int frames) { return ring.read(frames); }

    public void setTargetRate(int rate) {
        if (rate > 0 && rate != targetRate) { targetRate = rate; ring.setRate(rate); }
    }

    private volatile boolean skip;
    private volatile boolean listMode;
    private volatile int listIndex;

    public boolean listMode() { return listMode; }
    public int listIndex() { return listIndex; }

    /** 文件名（模糊匹配）；成功返回实际文件名，失败返回 null */
    public synchronized String play(String wanted, boolean loop) {
        String match = match(wanted);
        if (match == null) return null;
        start(List.of(match), false, 0);
        return match;
    }

    /** 按顺序播放一组文件；播完最后一个：循环开着就从头再来，否则停止 */
    public synchronized boolean playList(List<String> names) {
        List<String> ok = new java.util.ArrayList<>();
        for (String n : names) { String m = match(n); if (m != null) ok.add(m); }
        if (ok.isEmpty()) return false;
        start(ok, true, 0);
        return true;
    }

    public synchronized boolean playListFrom(List<String> names, int index) {
        if (index < 0 || index >= names.size()) return false;
        String selected = names.get(index);
        List<String> ok = new java.util.ArrayList<>();
        for (String n : names) { String m = match(n); if (m != null) ok.add(m); }
        int actual = ok.indexOf(selected);
        if (actual < 0) return false;
        start(ok, true, actual);
        return true;
    }

    /** 跳到下一首（单文件时等于停止或循环重播） */
    public void next() { skip = true; }

    private String match(String wanted) {
        String w = wanted.toLowerCase(Locale.ROOT);
        String match = null;
        for (String f : files()) {
            if (f.equalsIgnoreCase(wanted)) return f;
            if (match == null && f.toLowerCase(Locale.ROOT).contains(w)) match = f;
        }
        return match;
    }

    private void start(List<String> names, boolean asList, int initialIndex) {
        stop();
        ring.setRate(targetRate);
        ring.clear();
        skip = false;
        listMode = asList;
        listIndex = initialIndex;
        Thread t = new Thread(() -> run(names, initialIndex), "Plasmo Voice Music File Player");
        t.setDaemon(true);
        thread = t;
        playing = names.get(initialIndex);
        t.start();
    }

    public synchronized void stop() {
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
            try { t.join(500); } catch (InterruptedException ignored) { }
        }
        ring.clear();
        monitor.reset();
        playing = "";
        listMode = false;
    }

    private void run(List<String> names, int initialIndex) {
        int i = initialIndex;
        try {
            while (thread == Thread.currentThread()) {
                String f = names.get(i);
                playing = f;
                listIndex = i;
                skip = false;
                try {
                    decodeOnce(dir().resolve(f));
                } catch (SkipException ignored) {
                }
                boolean wasSkip = skip;
                if (!wasSkip) {
                    ring.finishInput();
                    while (thread == Thread.currentThread() && !skip && ring.frames() > 0) Thread.sleep(10);
                    wasSkip = skip;
                    ring.resumeInput();
                }
                if (wasSkip) { ring.clear(); monitor.reset(); }
                i = PlaybackOrder.next(Config.get().mode(), names.size(), i);
                if (i < 0) break;
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            Chat.say("&c无法播放 &f" + playing + " &7" + e.getMessage());
            PvMusicMod.LOGGER.warn("播放文件失败", e);
        } finally {
            if (thread == Thread.currentThread()) {
                thread = null;
                playing = "";
                listMode = false;
                // 播完了: 菜单里的“播放文件”回到“不播放”
                net.minecraft.client.Minecraft.getInstance().execute(PvMusicUi::sync);
            }
        }
    }

    private static final class SkipException extends RuntimeException {
        SkipException() { super(null, null, false, false); }
    }

    private void decodeOnce(Path file) throws Exception {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".m4a")) { decodeM4a(file); return; }
        if (n.endsWith(".wav") || n.endsWith(".aiff") || n.endsWith(".au")) { decodeJavaSound(file); return; }
        try (InputStream raw = Files.newInputStream(file);
             BufferedInputStream in = new BufferedInputStream(raw)) {
            in.mark(16);
            byte[] h = in.readNBytes(12);
            in.reset();
            if (starts(h, "fLaC")) decodeFlac(in);
            else if (starts(h, "OggS")) decodeOgg(in);
            else if (starts(h, "ID3") || (h.length >= 2 && (h[0] & 255) == 255 && (h[1] & 0xe0) == 0xe0)) decodeMp3(in);
            else throw new IllegalStateException("无法识别文件内部的音频格式");
        }
    }
    private static boolean starts(byte[] data, String prefix) {
        byte[] b = prefix.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (data.length < b.length) return false;
        for (int i = 0; i < b.length; i++) if (data[i] != b[i]) return false;
        return true;
    }

    private void decodeFlac(InputStream in) throws Exception {
        org.jflac.FLACDecoder decoder = new org.jflac.FLACDecoder(in);
        final int[] format = new int[3];
        decoder.addPCMProcessor(new org.jflac.PCMProcessor() {
            public void processStreamInfo(org.jflac.metadata.StreamInfo info) {
                format[0] = info.getSampleRate(); format[1] = info.getChannels(); format[2] = info.getBitsPerSample();
                if (format[2] != 8 && format[2] != 16 && format[2] != 24) throw new IllegalArgumentException("不支持此 FLAC 位深: " + format[2]);
            }
            public void processPCM(org.jflac.util.ByteData pcm) {
                int channels = format[1], bits = format[2], bytesPerSample = bits / 8;
                byte[] data = pcm.getData();
                int count = pcm.getLen() / (channels * bytesPerSample);
                short[] out = new short[count * 2];
                for (int i = 0; i < count; i++) for (int c = 0; c < 2; c++) {
                    int o = (i * channels + (c < channels ? c : 0)) * bytesPerSample;
                    int v = bits == 8 ? ((data[o] & 255) - 128) << 8 : bits == 16 ? (short)((data[o] & 255) | (data[o+1] << 8)) : (short)((data[o+1] & 255) | (data[o+2] << 8));
                    out[i * 2 + c] = (short)v;
                }
                try { feed(out, format[0]); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new DecodeInterrupted(); }
            }
        });
        try { decoder.decode(); } catch (DecodeInterrupted e) { throw new InterruptedException(); }
    }
    private static final class DecodeInterrupted extends RuntimeException { }

    private void decodeM4a(Path file) throws Exception {
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(file.toFile(), "r")) {
            var input = net.sourceforge.jaad.mp4.MP4InputStream.open(f);
            var movie = new net.sourceforge.jaad.mp4.MP4Container(input).getMovie();
            var tracks = movie.getTracks(net.sourceforge.jaad.mp4.api.AudioTrack.AudioCodec.AAC);
            if (tracks.isEmpty()) throw new IllegalStateException("M4A 中没有支持的 AAC 音轨");
            var track = tracks.get(0);
            if (track.getProtection() != null || track.getDecoderSpecificInfo() == null) throw new IllegalStateException("此 M4A 音轨受保护或格式不支持");
            var decoder = net.sourceforge.jaad.aac.Decoder.create(track.getDecoderSpecificInfo().getData());
            var buffer = new net.sourceforge.jaad.SampleBuffer();
            buffer.setBigEndian(false);
            while (track.hasMoreFrames()) {
                decoder.decodeFrame(track.readNextFrame().getData(), buffer);
                int channels = buffer.getChannels();
                if (channels < 1 || buffer.getBitsPerSample() != 16) throw new IllegalStateException("不支持此 AAC 输出格式");
                byte[] data = buffer.getData();
                int frames = data.length / (channels * 2);
                short[] out = new short[frames * 2];
                for (int i=0;i<frames;i++) for (int c=0;c<2;c++) {
                    int o = (i * channels + (c < channels ? c : 0)) * 2;
                    out[i*2+c] = (short)((data[o] & 255) | (data[o+1] << 8));
                }
                feed(out, buffer.getSampleRate());
            }
        }
    }

    /** 一段立体声交错数据（任意采样率）→ 重采样 → 进缓冲（按消耗速度等待） */
    private void feed(short[] stereo, int srcRate) throws InterruptedException {
        if (thread != Thread.currentThread()) throw new InterruptedException();
        if (skip) throw new SkipException();
        short[] out = resampler.process(stereo, srcRate, targetRate);
        int total = out.length / 2;
        int chunk = Math.max(1, targetRate / 50);
        for (int pos = 0; pos < total; pos += chunk) {
            if (thread != Thread.currentThread()) throw new InterruptedException();
            if (skip) throw new SkipException();
            ring.awaitSpace(300);
            int count = Math.min(chunk, total - pos);
            ring.push(out, pos, count);
            monitor.write(java.util.Arrays.copyOfRange(out, pos * 2, (pos + count) * 2), targetRate);
        }
    }

    private void decodeJavaSound(Path file) throws Exception {
        try (AudioInputStream raw = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat src = raw.getFormat();
            AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, src.getSampleRate(), 16, src.getChannels(),
                    src.getChannels() * 2, src.getSampleRate(), false);
            try (AudioInputStream in = AudioSystem.getAudioInputStream(pcm, raw)) {
                int ch = pcm.getChannels();
                int rate = (int) pcm.getSampleRate();
                byte[] buf = new byte[rate / 50 * ch * 2];
                int fill = 0;
                while (true) {
                    int r = in.read(buf, fill, buf.length - fill);
                    if (r < 0) break;
                    fill += r;
                    int frames = fill / (ch * 2);
                    if (frames == 0) continue;
                    short[] st = new short[frames * 2];
                    for (int i = 0; i < frames; i++) {
                        int o = i * ch * 2;
                        short l = (short) ((buf[o] & 0xff) | (buf[o + 1] << 8));
                        short rr = ch >= 2 ? (short) ((buf[o + 2] & 0xff) | (buf[o + 3] << 8)) : l;
                        st[i * 2] = l; st[i * 2 + 1] = rr;
                    }
                    feed(st, rate);
                    int used = frames * ch * 2;
                    System.arraycopy(buf, used, buf, 0, fill - used);
                    fill -= used;
                }
            }
        }
    }

    private void decodeOgg(InputStream in) throws Exception {
        byte[] bytes = in.readAllBytes();
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
        long decoder = 0;
        ShortBuffer chunk = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            data.put(bytes).flip();
            IntBuffer error = stack.mallocInt(1);
            decoder = STBVorbis.stb_vorbis_open_memory(data, error, null);
            if (decoder == 0) throw new IllegalStateException("ogg 解码失败: " + error.get(0));
            var info = STBVorbis.stb_vorbis_get_info(decoder, org.lwjgl.stb.STBVorbisInfo.malloc(stack));
            int channels = info.channels(), rate = info.sample_rate();
            if (channels < 1 || channels > 8 || rate < 8000 || rate > 192000) throw new IllegalStateException("不支持此 OGG 声道数或采样率");
            chunk = MemoryUtil.memAllocShort(Math.max(1, rate / 50) * channels);
            while (true) {
                int frames = STBVorbis.stb_vorbis_get_samples_short_interleaved(decoder, channels, chunk);
                if (frames <= 0) break;
                short[] stereo = new short[frames * 2];
                for (int i = 0; i < frames; i++) {
                    short left = chunk.get(i * channels);
                    stereo[i * 2] = left;
                    stereo[i * 2 + 1] = channels >= 2 ? chunk.get(i * channels + 1) : left;
                }
                feed(stereo, rate);
            }
        } finally {
            if (decoder != 0) STBVorbis.stb_vorbis_close(decoder);
            if (chunk != null) MemoryUtil.memFree(chunk);
            MemoryUtil.memFree(data);
        }
    }

    private void decodeMp3(InputStream source) throws Exception {
        try (InputStream is = new BufferedInputStream(source)) {
            Bitstream bs = new Bitstream(is);
            Decoder dec = new Decoder();
            Header h;
            while ((h = bs.readFrame()) != null) {
                SampleBuffer sb = (SampleBuffer) dec.decodeFrame(h, bs);
                short[] buf = sb.getBuffer();
                int c = sb.getChannelCount();
                int frames = sb.getBufferLength() / c;
                short[] st = new short[frames * 2];
                for (int i = 0; i < frames; i++) {
                    short l = buf[i * c];
                    st[i * 2] = l; st[i * 2 + 1] = c >= 2 ? buf[i * c + 1] : l;
                }
                feed(st, sb.getSampleFrequency());
                bs.closeFrame();
            }
            bs.close();
        }
    }
}
