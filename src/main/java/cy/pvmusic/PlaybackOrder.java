package cy.pvmusic;

import java.util.concurrent.ThreadLocalRandom;

/** 返回下一首的索引；-1 表示顺序播放结束。 */
final class PlaybackOrder {
    private PlaybackOrder() { }
    static int next(String mode, int count, int current) {
        if (count <= 0) return -1;
        if (current < 0 || current >= count) return 0;
        if ("shuffle".equals(mode)) {
            if (count == 1) return 0;
            int choice = ThreadLocalRandom.current().nextInt(count - 1);
            return choice >= current ? choice + 1 : choice;
        }
        if (current + 1 < count) return current + 1;
        return "loop".equals(mode) ? 0 : -1;
    }
}
