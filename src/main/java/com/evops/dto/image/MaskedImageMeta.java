package com.evops.dto.image;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 按岗位脱敏后的影像版本元数据统一视图。
 * 不允许该岗位看到的字段直接为 null（图像字节从不脱敏，仅元数据遮蔽）。
 */
@Data
public class MaskedImageMeta {

    private Long archiveId;

    private Long batchId;

    private String batchNo;

    private Integer stageNo;

    private String viewType;

    private Integer versionNo;

    private String status;

    private String fileName;

    private String photographer;

    /** 低权限岗位只精确到日期（时间部分 00:00:00） */
    private LocalDateTime shotAt;

    private String cameraModel;

    private String sha256;

    private String contentType;

    private Integer widthPx;

    private Integer heightPx;

    private Long sizeBytes;

    private LocalDateTime voidedAt;
}
