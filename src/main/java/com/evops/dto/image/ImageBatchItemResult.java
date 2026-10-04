package com.evops.dto.image;

import lombok.Data;

/**
 * 批量上传中单张照片的处理结果。
 * status：ARCHIVED 已归档 / DEDUPED 重复内容只保留引用 / REJECTED 驳回。
 */
@Data
public class ImageBatchItemResult {

    private int index;

    private String fileName;

    private String viewType;

    private String status;

    /** ARCHIVED/DEDUPED 回指的版本号 */
    private Integer versionNo;

    /** 可见岗位才回传内容指纹（见岗位脱敏矩阵） */
    private String sha256;

    /** REJECTED 时的具体原因；其余状态为说明文案 */
    private String reason;

    public static ImageBatchItemResult of(int index, String fileName, String viewType,
                                          String status, Integer versionNo,
                                          String sha256, String reason) {
        ImageBatchItemResult item = new ImageBatchItemResult();
        item.index = index;
        item.fileName = fileName;
        item.viewType = viewType;
        item.status = status;
        item.versionNo = versionNo;
        item.sha256 = sha256;
        item.reason = reason;
        return item;
    }
}
