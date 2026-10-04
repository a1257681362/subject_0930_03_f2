package com.evops.dto.image;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 拍摄批次详情（元数据已按岗位脱敏）。
 */
@Data
public class ImageBatchDetail {

    private Long id;

    private String batchNo;

    private Long relicId;

    private Long projectId;

    private Integer stageNo;

    private String status;

    private String voidReason;

    private LocalDateTime voidedAt;

    private String uploadRole;

    private Integer itemCount;

    private Integer dedupCount;

    private Integer rejectCount;

    private LocalDateTime createTime;

    private List<MaskedImageMeta> items = new ArrayList<>();
}
