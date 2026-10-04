package com.evops.service.support;

import com.evops.constant.StaffRole;
import com.evops.dto.image.MaskedImageMeta;
import com.evops.entity.ImageArchive;
import com.evops.entity.ImageBlob;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 影像元数据岗位脱敏的唯一入口：列表/详情 JSON 与文件下载响应头共用，
 * 避免两处遮蔽规则漂移。图像字节永不修改，只遮蔽随附元数据。
 */
@Component
public class ImageMetadataMasker {

    public MaskedImageMeta mask(ImageArchive archive, ImageBlob blob, String batchNo, StaffRole role) {
        MaskedImageMeta meta = new MaskedImageMeta();
        meta.setArchiveId(archive.getId());
        meta.setBatchId(archive.getBatchId());
        meta.setBatchNo(batchNo);
        meta.setStageNo(archive.getStageNo());
        meta.setViewType(archive.getViewType());
        meta.setVersionNo(archive.getVersionNo());
        meta.setStatus(archive.getStatus());
        meta.setFileName(archive.getFileName());
        meta.setVoidedAt(archive.getVoidedAt());

        // 摄影者：VIEWER 遮蔽
        if (role.canSeePhotographer()) {
            meta.setPhotographer(archive.getPhotographer());
        }
        // 拍摄时间：CURATOR/VIEWER 仅到日期
        LocalDateTime shotAt = archive.getShotAt();
        if (shotAt != null) {
            meta.setShotAt(role.getShotPrecision() == StaffRole.ShotPrecision.FULL
                    ? shotAt
                    : shotAt.truncatedTo(ChronoUnit.DAYS));
        }
        if (blob != null) {
            // 机型：CURATOR/VIEWER 遮蔽
            if (role.canSeeCamera()) {
                meta.setCameraModel(blob.getCameraModel());
            }
            // 内容指纹：VIEWER 遮蔽
            if (role.canSeeFingerprint()) {
                meta.setSha256(blob.getSha256());
            }
            meta.setContentType(blob.getContentType());
            meta.setWidthPx(blob.getWidthPx());
            meta.setHeightPx(blob.getHeightPx());
            meta.setSizeBytes(blob.getSizeBytes());
        }
        return meta;
    }

    /**
     * 文件下载响应头：只放该岗位允许看到的元数据键，不允许的键直接不出现。
     * key/value 均为已编码、可直接写入 HTTP 头的字符串。
     */
    public Map<String, String> downloadHeaders(ImageArchive archive, ImageBlob blob, StaffRole role) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Image-View-Type", archive.getViewType());
        headers.put("X-Image-Version-No", String.valueOf(archive.getVersionNo()));
        headers.put("X-Image-Stage-No", String.valueOf(archive.getStageNo()));
        if (role.canSeePhotographer()) {
            headers.put("X-Image-Photographer", header(archive.getPhotographer()));
        }
        if (archive.getShotAt() != null) {
            String shot = role.getShotPrecision() == StaffRole.ShotPrecision.FULL
                    ? archive.getShotAt().toString()
                    : archive.getShotAt().toLocalDate().toString();
            headers.put("X-Image-Shot-At", shot);
        }
        if (blob != null) {
            if (role.canSeeCamera()) {
                headers.put("X-Image-Camera-Model", header(blob.getCameraModel()));
            }
            if (role.canSeeFingerprint()) {
                headers.put("X-Image-Sha256", blob.getSha256());
            }
            headers.put("X-Image-Width", String.valueOf(blob.getWidthPx()));
            headers.put("X-Image-Height", String.valueOf(blob.getHeightPx()));
        }
        return headers;
    }

    private String header(String value) {
        if (value == null) {
            return "";
        }
        // 自定义头不能直接放中文：百分号编码（UTF-8 在 JVM 中必然支持）
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
