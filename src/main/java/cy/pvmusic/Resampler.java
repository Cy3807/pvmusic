package cy.pvmusic;

/** 立体声交错数据的线性重采样（流式，保留状态） */
public final class Resampler {
    private double pos;
    private short pl, pr;
    private int lastSrc, lastDst;

    public short[] process(short[] in, int srcRate, int dstRate) {
        if (srcRate == dstRate) return in;
        if (srcRate != lastSrc || dstRate != lastDst) { pos = 0; lastSrc = srcRate; lastDst = dstRate; }
        int n = in.length / 2;
        if (n == 0) return in;
        double step = (double) srcRate / dstRate;
        int outMax = (int) (n / step) + 2;
        short[] out = new short[outMax * 2];
        int o = 0;
        while (pos < n) {
            int i0 = (int) pos;
            double fr = pos - i0;
            short l0 = i0 == 0 ? pl : in[(i0 - 1) * 2], r0 = i0 == 0 ? pr : in[(i0 - 1) * 2 + 1];
            short l1 = in[i0 * 2], r1 = in[i0 * 2 + 1];
            out[o * 2] = (short) (l0 + (l1 - l0) * fr);
            out[o * 2 + 1] = (short) (r0 + (r1 - r0) * fr);
            o++;
            pos += step;
        }
        pos -= n;
        pl = in[(n - 1) * 2]; pr = in[(n - 1) * 2 + 1];
        return java.util.Arrays.copyOf(out, o * 2);
    }
}
