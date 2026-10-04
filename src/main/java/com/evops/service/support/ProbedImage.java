package com.evops.service.support;

import java.time.LocalDateTime;

/**
 * 单张上传文件的技术探测结果（不做业务判定）。
 * 不可读（无法识别的图片格式）时 readable=false、尺寸为 null，错误由上层组装原因。
 */
public class ProbedImage {

    private final boolean readable;
    private final byte[] bytes;
    private final String sha256;
    private final String contentType;
    private final Integer width;
    private final Integer height;
    private final String cameraModel;
    private final String exifPhotographer;
    private final LocalDateTime exifShotAt;

    public ProbedImage(boolean readable, byte[] bytes, String sha256, String contentType,
                       Integer width, Integer height, String cameraModel,
                       String exifPhotographer, LocalDateTime exifShotAt) {
        this.readable = readable;
        this.bytes = bytes;
        this.sha256 = sha256;
        this.contentType = contentType;
        this.width = width;
        this.height = height;
        this.cameraModel = cameraModel;
        this.exifPhotographer = exifPhotographer;
        this.exifShotAt = exifShotAt;
    }

    public boolean isReadable() { return readable; }
    public byte[] getBytes() { return bytes; }
    public String getSha256() { return sha256; }
    public String getContentType() { return contentType; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public String getCameraModel() { return cameraModel; }
    public String getExifPhotographer() { return exifPhotographer; }
    public LocalDateTime getExifShotAt() { return exifShotAt; }
}
