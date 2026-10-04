package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 拍摄批次：一次批量上传一行，挂到某修复项目的某个修复阶段。
 * 作废只把 status 置 VOIDED，批次与照片行、原文件全部保留。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_image_batch")
public class ImageBatch extends BaseEntity {

    private String batchNo;

    private Long relicId;

    private Long projectId;

    private Integer stageNo;

    /** 批次级摄影者兜底值 */
    private String photographer;

    /** 批次级拍摄时间兜底值 */
    private LocalDateTime shotAt;

    /** 实际归档（ARCHIVED）张数 */
    private Integer itemCount;

    /** 去重回指张数 */
    private Integer dedupCount;

    /** 驳回张数 */
    private Integer rejectCount;

    /** 上传岗位：RESTORER / ARCHIVIST */
    private String uploadRole;

    /** ACTIVE / VOIDED */
    private String status;

    private String voidReason;

    private LocalDateTime voidedAt;
}
