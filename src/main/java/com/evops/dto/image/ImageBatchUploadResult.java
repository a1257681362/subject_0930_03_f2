package com.evops.dto.image;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量上传结果：允许部分成功。逐张给出具体状态与原因。
 */
@Data
public class ImageBatchUploadResult {

    private String batchNo;

    private Long batchId;

    private Long projectId;

    private Integer stageNo;

    private int archivedCount;

    private int dedupedCount;

    private int rejectedCount;

    private List<ImageBatchItemResult> items = new ArrayList<>();
}
