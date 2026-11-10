package cy.pvmusic;

/**
 * 立体声 s16 交错的环形缓冲（固定采样率）。读的一侧按固定节奏取帧，写的一侧时快时慢，
 * 所以: 开始前先攒够 100ms；缓冲偏多时每次多丢 1 帧（几乎听不出来）慢慢追回去，而不是一次丢一大段；
 * 真的读空了就补最后一帧并重新攒。
 */
public final class PcmRing {
    private static final int TARGET_MS = 100;
    private static final int MAX_MS = 400;
    private final boolean adaptive;
    public PcmRing() { this(true); }
    public PcmRing(boolean adaptive) { this.adaptive = adaptive; }

    private final Object lock = new Object();
    private short[] buf = new short[0];
    private int len; // 样本数（立体声 = 帧数*2）
    private int rate = 48000;
    private boolean primed;
    private boolean finishing;
    private volatile int underruns;

    public void setRate(int newRate) {
        synchronized (lock) {
            if (newRate == rate && buf.length > 0) return;
            rate = newRate;
            buf = new short[rate * 2]; // 1 秒
            len = 0;
            rpos = 0;
            primed = false;
        }
    }

    public void clear() {
        synchronized (lock) { len = 0; rpos = 0; primed = false; finishing = false; lock.notifyAll(); }
    }

    public void finishInput() { synchronized (lock) { finishing = true; lock.notifyAll(); } }
    public void resumeInput() { synchronized (lock) { finishing = false; } }

    public int frames() {
        synchronized (lock) { return len / 2; }
    }

    public int underruns() { return underruns; }

    /** 写入 frames 帧（data 从 offsetFrame 帧起） */
    public void push(short[] data, int offsetFrame, int frames) {
        synchronized (lock) {
            if (buf.length == 0) buf = new short[rate * 2];
            int add = frames * 2;
            int cap = buf.length;
            if (add > cap) { offsetFrame += (add - cap) / 2; add = cap; }
            if (len + add > cap) {
                int drop = len + add - cap;
                System.arraycopy(buf, drop, buf, 0, len - drop);
                len -= drop;
            }
            System.arraycopy(data, offsetFrame * 2, buf, len, add);
            len += add;
        }
    }

    /** 文件播放用: 缓冲里超过 maxMs 就等（让解码线程按消耗速度走） */
    public void awaitSpace(int maxMs) throws InterruptedException {
        synchronized (lock) {
            while (len / 2 > rate * maxMs / 1000) lock.wait(20);
        }
    }

    private double rpos; // 读位置的小数部分（帧）
    private int reads;

    /** 状态摘要，给日志用 */
    public synchronized String stats() {
        return "缓冲 " + (len / 2 * 1000 / Math.max(1, rate)) + "ms, 读空 " + underruns + " 次, 读 " + reads + " 帧";
    }

    /**
     * 取 frames 帧；还没攒够返回 null。
     * 麦克风帧的节拍和系统声音的节拍来自两个不同的时钟，会慢慢漂移。这里按缓冲多少微调读取速度（最多 ±2%，
     * 线性插值），缓冲偏多就读快一点、偏少就读慢一点，让缓冲稳定在 100ms 左右，不再出现“攒满丢一段 / 读空断一段”。
     */
    public short[] read(int frames) {
        synchronized (lock) {
            reads++;
            int have = len / 2;
            int targetF = rate * TARGET_MS / 1000;
            if (finishing && have == 0) return null;
            if (finishing && have <= frames + 2) {
                short[] out = new short[frames * 2];
                int n = Math.min(len, out.length) & ~1;
                System.arraycopy(buf, 0, out, 0, n);
                len = 0; rpos = 0; primed = false;
                lock.notifyAll();
                return out;
            }
            if (!primed) {
                if (!finishing && have < Math.max(targetF, frames + 2)) return null;
                primed = true;
                rpos = 0;
            }
            if (have > rate * MAX_MS / 1000) { // 积压太多（比如线程卡了一下）: 一次回到目标
                int drop = have - targetF;
                System.arraycopy(buf, drop * 2, buf, 0, len - drop * 2);
                len -= drop * 2;
                have = len / 2;
            }
            double fill = have - rpos;
            double ratio = adaptive ? 1.0 + Math.max(-0.02, Math.min(0.02, (fill - targetF) / targetF * 0.02)) : 1.0;
            if (fill < frames * ratio + 2) {
                // 快读空了: 这一帧放慢读（最多慢到一半），还不够才算真的读空
                double maxRatio = (fill - 2) / frames;
                if (maxRatio < 0.5) {
                    underruns++;
                    short[] out = new short[frames * 2];
                    int n = Math.min(len, frames * 2) & ~1;
                    System.arraycopy(buf, 0, out, 0, n);
                    short l = n >= 2 ? buf[n - 2] : 0, r = n >= 2 ? buf[n - 1] : 0;
                    for (int i = n; i < out.length; i += 2) { out[i] = l; out[i + 1] = r; }
                    len = 0;
                    rpos = 0;
                    primed = false;
                    lock.notifyAll();
                    return out;
                }
                ratio = maxRatio;
            }
            short[] out = new short[frames * 2];
            for (int i = 0; i < frames; i++) {
                double p = rpos + i * ratio;
                int i0 = (int) p;
                double fr = p - i0;
                int a0 = i0 * 2, a1 = a0 + 2;
                out[i * 2] = (short) (buf[a0] + (buf[a1] - buf[a0]) * fr);
                out[i * 2 + 1] = (short) (buf[a0 + 1] + (buf[a1 + 1] - buf[a0 + 1]) * fr);
            }
            rpos += frames * ratio;
            int k = (int) rpos;
            rpos -= k;
            System.arraycopy(buf, k * 2, buf, 0, len - k * 2);
            len -= k * 2;
            lock.notifyAll();
            return out;
        }
    }
}
