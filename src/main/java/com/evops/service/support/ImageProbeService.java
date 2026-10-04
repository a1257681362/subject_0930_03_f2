package com.evops.service.support;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * 上传照片的技术探测：
 * 1) SHA-256 内容指纹（去重依据）；
 * 2) JDK ImageIO 实测分辨率（不解码整图，只读图片头尺寸），只收 JPEG/PNG；
 * 3) metadata-extractor 读 EXIF：拍摄时间 DateTimeOriginal、摄影者 Artist、机型 Model；
 *    读不到（PNG、无 EXIF、损坏段）一律按缺失处理，不因此拒收。
 */
@Component
public class ImageProbeService {

    private static final Logger log = LoggerFactory.getLogger(ImageProbeService.class);

    public ProbedImage probe(byte[] bytes, String declaredContentType) {
        String sha256 = sha256Hex(bytes);

        Integer width = null;
        Integer height = null;
        String formatName = null;
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis != null) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(iis, true, true);
                        width = reader.getWidth(0);
                        height = reader.getHeight(0);
                        formatName = reader.getFormatName();
                    } finally {
                        reader.dispose();
                    }
                }
            }
        } catch (IOException e) {
            log.debug("ImageIO 读取图片尺寸失败", e);
        }

        if (formatName == null) {
            return new ProbedImage(false, bytes, sha256, null, null, null, null, null, null);
        }
        String contentType = normalizeContentType(formatName, declaredContentType);
        if (contentType == null) {
            // 能解码但不是本期受理的 JPEG/PNG：同样按不可归档处理，由上层给原因
            return new ProbedImage(false, bytes, sha256, null, width, height, null, null, null);
        }

        ExifSnapshot exif = readExif(bytes);
        return new ProbedImage(true, bytes, sha256, contentType, width, height,
                exif.cameraModel, exif.photographer, exif.shotAt);
    }

    private String normalizeContentType(String formatName, String declared) {
        String fmt = formatName.trim().toUpperCase(Locale.ROOT);
        if ("JPEG".equals(fmt) || "JPG".equals(fmt)) {
            return "image/jpeg";
        }
        if ("PNG".equals(fmt)) {
            return "image/png";
        }
        return null;
    }

    private ExifSnapshot readExif(byte[] bytes) {
        ExifSnapshot snapshot = new ExifSnapshot();
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(
                    new BufferedInputStream(new ByteArrayInputStream(bytes)));

            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (ifd0 != null) {
                snapshot.photographer = trimToNull(ifd0.getString(ExifIFD0Directory.TAG_ARTIST));
                snapshot.cameraModel = trimToNull(ifd0.getString(ExifIFD0Directory.TAG_MODEL));
            }
            ExifSubIFDDirectory subIfd = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (subIfd != null && subIfd.containsTag(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)) {
                Date shot = subIfd.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
                if (shot != null) {
                    snapshot.shotAt = LocalDateTime.ofInstant(shot.toInstant(), ZoneId.systemDefault());
                }
            }
        } catch (Exception e) {
            // 无 EXIF（PNG、截图）或 EXIF 段损坏：按缺失处理，表单字段兜底
            log.debug("EXIF 解析跳过：{}", e.getMessage());
        }
        return snapshot;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 不支持 SHA-256", e);
        }
    }

    private static class ExifSnapshot {
        private String cameraModel;
        private String photographer;
        private LocalDateTime shotAt;
    }
}
