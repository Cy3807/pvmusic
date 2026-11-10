package cy.pvmusic;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** 只读取本地文件内嵌的封面，不联网。 */
final class CoverArtReader {
    private static final int MAX_ART_BYTES = 8 * 1024 * 1024;
    private CoverArtReader() { }
    static BufferedImage read(Path path) throws Exception {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        byte[] art;
        {
            var audio = org.jaudiotagger.audio.AudioFileIO.read(path.toFile());
            var tag = audio.getTag();
            var cover = tag == null ? null : tag.getFirstArtwork();
            art = cover == null ? null : cover.getBinaryData();
        }
        if (art == null || art.length == 0 || art.length > MAX_ART_BYTES) return null;
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(art))) {
            if (stream == null) return null;
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > 16000 || h > 16000) return null;
                var param = reader.getDefaultReadParam();
                int step = Math.max(1, Math.max(w, h) / 128);
                param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage original = reader.read(0, param);
                if (original == null) return null;
                BufferedImage thumb = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
                var g = thumb.createGraphics();
                try { g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR); g.drawImage(original, 0, 0, 64, 64, null); }
                finally { g.dispose(); original.flush(); }
                return thumb;
            } finally { reader.dispose(); }
        }
    }
    private static int size(DataInputStream in, int max) throws IOException {
        int n = Integer.reverseBytes(in.readInt());
        if (n < 0 || n > max) throw new IOException("无效的封面长度");
        return n;
    }
}
