package com.evops.dto.image;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 影像拍摄批次上传表单（multipart/form-data）。
 * 字段名与表单字段名严格一致：files / viewTypes / shotTimes / photographers。
 * shotAt 用 String 接收，由服务层按 ISO-8601 解析，避免绑定期抛出不明 BindException。
 */
@Data
public class ImageBatchUploadForm {

    @NotNull(message = "修复项目 id 不能为空")
    private Long projectId;

    @NotNull(message = "修复阶段号不能为空")
    private Integer stageNo;

    /** 批次级摄影者兜底（EXIF、逐项 photographers 都缺失时使用） */
    private String photographer;

    /** 批次级拍摄时间兜底，ISO-8601 本地时间 yyyy-MM-ddTHH:mm:ss */
    private String shotAt;

    @NotEmpty(message = "批次至少上传一张照片")
    private MultipartFile[] files;

    /** 与 files 等长、按下标对齐：FRONT/BACK/BOTTOM/DETAIL */
    private List<String> viewTypes;

    /** 可选，与 files 等长：逐项拍摄时间兜底，覆盖批次级 */
    private List<String> shotTimes;

    /** 可选，与 files 等长：逐项摄影者兜底，覆盖批次级 */
    private List<String> photographers;
}
