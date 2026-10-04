package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 影像版本归档：一张照片在「项目 + 视角」上的一个版本一行，引用物理 blob。
 * version_no 在 (项目,视角) 内跨修复阶段递增，作废也不回收版本号。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_image_archive")
public class ImageArchive extends BaseEntity {

    private Long batchId;

    private Long relicId;

    private Long projectId;

    private Integer stageNo;

    /** FRONT / BACK / BOTTOM / DETAIL */
    private String viewType;

    /** 视角内版本号，从 1 递增，不回收 */
    private Integer versionNo;

    private Long blobId;

    /** 上传时的原始文件名（内容寻址 blob 被多版本复用，文件名属于归档引用） */
    private String fileName;

    /** 摄影者：EXIF 优先，表单兜底 */
    private String photographer;

    /** 拍摄时间：EXIF 优先，表单兜底 */
    private LocalDateTime shotAt;

    /** ACTIVE / VOIDED */
    private String status;

    private LocalDateTime voidedAt;
}
