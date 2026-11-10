package cy.pvmusic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** 信息提示统一风格: 灰 &7、重点白 &f、正向绿 &a、反向红 &c；前缀 [PVM] */
public final class Chat {
    public static final String PREFIX = "&7[&fPVM&7] ";

    private Chat() {}

    public static String parse(String s) {
        return s.replace('&', '§');
    }

    public static void say(String msg) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.displayClientMessage(Component.literal(parse(PREFIX + msg)), false);
        });
    }
}
