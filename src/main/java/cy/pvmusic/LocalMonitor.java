package cy.pvmusic;

import javax.sound.sampled.*;
import java.util.concurrent.ArrayBlockingQueue;

/** 本地输出在线程中执行，音频设备操作不会阻塞音乐发送。 */
public final class LocalMonitor {
    private record Block(short[] samples, int rate, long generation) { }
    private final ArrayBlockingQueue<Block> queue = new ArrayBlockingQueue<>(32);
    private SourceDataLine line;
    private int lineRate;
    private long retryAt;
    private volatile long generation;
    private long activeGeneration;

    public LocalMonitor() {
        Thread worker = new Thread(this::run, "Plasmo Voice Music Local Output");
        worker.setDaemon(true);
        worker.start();
    }

    public void write(short[] stereo, int rate) throws InterruptedException {
        Block b = new Block(stereo, rate, generation);
        if (!queue.offer(b, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) { queue.poll(); queue.offer(b); }
    }
    public void reset() {
        long next = ++generation;
        queue.clear();
        queue.offer(new Block(null, 0, next));
    }

    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Block block = queue.poll(2, java.util.concurrent.TimeUnit.SECONDS);
                if (block == null) { closeLine(); continue; }
                if (block.generation() != activeGeneration) {
                    if (line != null) { line.stop(); line.flush(); }
                    activeGeneration = block.generation();
                }
                if (block.samples() == null || block.generation() != generation || System.currentTimeMillis() < retryAt) continue;
                try {
                    if (line == null || lineRate != block.rate()) {
                        closeLine();
                        AudioFormat f = new AudioFormat(block.rate(), 16, 2, true, false);
                        SourceDataLine l = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, f));
                        l.open(f, block.rate() * 4 / 5);
                        line = l;
                        lineRate = block.rate();
                    }
                    short[] samples = block.samples();
                    byte[] bytes = new byte[samples.length * 2];
                    double gain = Config.get().localVolume / 100.0;
                    for (int i = 0; i < samples.length; i++) {
                        int v = (int) Math.round(samples[i] * gain);
                        v = Math.max(-32768, Math.min(32767, v));
                        bytes[i * 2] = (byte) v;
                        bytes[i * 2 + 1] = (byte) (v >> 8);
                    }
                    // 在独立线程等待设备，不因短暂写不下而整帧丢弃。
                    line.write(bytes, 0, bytes.length);
                    if (!line.isRunning() && line.getBufferSize() - line.available() >= block.rate() * 4 * 60 / 1000) line.start();
                } catch (Exception e) {
                    PvMusicMod.LOGGER.warn("本地播放失败，10 秒后重试", e);
                    closeLine();
                    retryAt = System.currentTimeMillis() + 10_000;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeLine();
    }
    public void idleCheck() { }
    public void close() { reset(); }
    private void closeLine() {
        if (line != null) {
            try { line.stop(); line.close(); } catch (Exception ignored) { }
            line = null;
        }
    }
}
