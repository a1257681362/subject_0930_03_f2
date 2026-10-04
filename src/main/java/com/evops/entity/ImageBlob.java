package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 影像物理文件：按内容 SHA-256 寻址，全局只有一份。
 * 作废任何归档版本都不删除 blob 行与磁盘文件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_image_blob")
public class ImageBlob extends BaseEntity {

    /** 内容 SHA-256（十六进制 64 位），全局唯一 */
    private String sha256;

    /** 相对仓库根目录的存储路径（ab/cd/sha） */
    private String storagePath;

    private String contentType;

    private Long sizeBytes;

    private Integer widthPx;

    private Integer heightPx;

    /** EXIF 相机机型（同字节同机型） */
    private String cameraModel;
}
