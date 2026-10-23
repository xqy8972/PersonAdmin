package com.project.person.admin.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;

/**
 * 图片压缩工具：识图上传前统一压缩，避免手机原图 base64 峰值打爆堆内存（512M 堆曾因此 OOM）。
 * 长边超过 1600 或体积超过 300KB 的图，等比缩到长边 1600 并重编码为 JPEG(0.85)；
 * 已经够小的原样返回。解码失败（非标准图片）回退原始字节，由后续逻辑报错。
 * 1600 是准确性/速度的平衡：再小配料表小字容易识别错，再大对速度影响明显。
 */
public final class ImageCompactor {

    private static final Logger log = LoggerFactory.getLogger(ImageCompactor.class);

    private ImageCompactor() {
    }

    public static byte[] compact(byte[] raw) {
        if (raw == null || raw.length <= 300 * 1024) {
            return raw;
        }
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
            if (src == null) {
                return raw;
            }
            int max = Math.max(src.getWidth(), src.getHeight());
            double scale = max > 1600 ? 1600.0 / max : 1.0;
            int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
            BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            writer.setOutput(ImageIO.createImageOutputStream(out));
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.write(null, new IIOImage(dst, null, null), param);
            writer.dispose();
            byte[] compressed = out.toByteArray();
            return compressed.length < raw.length ? compressed : raw;
        } catch (Exception e) {
            log.warn("图片压缩失败，使用原图: {}", e.getMessage());
            return raw;
        }
    }
}
