package cy.pvmusic;

import org.jetbrains.annotations.NotNull;
import su.plo.voice.api.client.audio.filter.AudioFilter;
import su.plo.voice.api.client.audio.filter.AudioFilterContext;

import java.util.List;

/**
 * 替换设备上原有的整条滤波链: 内部按原顺序执行原来的滤波器(增益、降噪……)，
 * 但只喂给它们纯麦克风帧，最后再叠加电脑音频。
 */
public final class MixFilter implements AudioFilter {
    private final List<AudioFilter> inner;
    private final AudioMixer mixer;

    public MixFilter(List<AudioFilter> inner, AudioMixer mixer) {
        this.inner = inner;
        this.mixer = mixer;
    }

    @Override
    public @NotNull String getName() { return "pvmusic_mix"; }

    @Override
    public boolean isEnabled() { return true; }

    @Override
    public short[] process(@NotNull AudioFilterContext context, short[] samples) {
        AudioMixer.Frame frame = mixer.pending(samples.length);
        short[] in = frame != null ? frame.mic().clone() : samples;

        AudioFilterContext ctx = new AudioFilterContext(context.getDevice());
        for (AudioFilter f : inner) {
            if (!f.isEnabled()) continue;
            if (f.getSupportedChannels() > 0 && f.getSupportedChannels() != ctx.getChannels()) continue;
            in = f.process(ctx, in);
        }
        return frame == null ? in : mixer.mixAfterFilters(in, ctx.getChannels(), frame);
    }
}
