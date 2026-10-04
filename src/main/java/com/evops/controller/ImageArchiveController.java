package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.constant.StaffRole;
import com.evops.dto.image.ImageBatchDetail;
import com.evops.dto.image.ImageBatchUploadForm;
import com.evops.dto.image.ImageBatchUploadResult;
import com.evops.dto.image.ImageVoidRequest;
import com.evops.dto.image.MaskedImageMeta;
import com.evops.entity.ImageBatch;
import com.evops.service.ImageArchiveService;
import com.evops.service.support.ImageMetadataMasker;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 文物多角度影像归档：拍摄批次上传、作废、批次详情、版本列表、原始文件下载。
 * 岗位由请求头 X-Staff-Role 声明；缺省/未知按最严格 VIEWER。
 */
@RestController
@RequestMapping("/api/image-archives")
public class ImageArchiveController {

    private static final String ROLE_HEADER = "X-Staff-Role";

    private final ImageArchiveService imageArchiveService;
    private final ImageMetadataMasker masker;

    public ImageArchiveController(ImageArchiveService imageArchiveService,
                                  ImageMetadataMasker masker) {
        this.imageArchiveService = imageArchiveService;
        this.masker = masker;
    }

    /** 批量上传：multipart/form-data；逐张返回 ARCHIVED/DEDUPED/REJECTED 及具体原因 */
    @PostMapping(value = "/batches", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImageBatchUploadResult> upload(
            @Valid @ModelAttribute ImageBatchUploadForm form,
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader) {
        StaffRole role = StaffRole.fromHeader(roleHeader);
        return ApiResponse.ok(imageArchiveService.uploadBatch(form, role));
    }

    /** 作废：只置状态，原文件保留（ARCHIVIST/CURATOR） */
    @PostMapping("/batches/{id}/void")
    public ApiResponse<ImageBatch> voidBatch(@PathVariable Long id,
                                             @Valid @RequestBody ImageVoidRequest request,
                                             @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader) {
        StaffRole role = StaffRole.fromHeader(roleHeader);
        return ApiResponse.ok(imageArchiveService.voidBatch(id, request.getReason(), role));
    }

    /** 批次详情（元数据按岗位脱敏） */
    @GetMapping("/batches/{id}")
    public ApiResponse<ImageBatchDetail> batch(@PathVariable Long id,
                                               @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader) {
        return ApiResponse.ok(imageArchiveService.getBatchDetail(id, StaffRole.fromHeader(roleHeader)));
    }

    /** 某项目（可指定视角）的版本历史，版本号倒序；含已作废版本（归档事实保留） */
    @GetMapping("/projects/{projectId}/versions")
    public ApiResponse<List<MaskedImageMeta>> versions(
            @PathVariable Long projectId,
            @RequestParam(value = "viewType", required = false) String viewType,
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader) {
        return ApiResponse.ok(imageArchiveService.listVersions(
                projectId, viewType, StaffRole.fromHeader(roleHeader)));
    }

    /**
     * 下载当前有效版本的原始字节。图像字节不脱敏；随附元数据按岗位写入自定义响应头。
     * 注意：目标解析（含无效/作废判定）必须在写出字节之前完成，异常才能走统一处理。
     */
    @GetMapping("/projects/{projectId}/views/{viewType}/file")
    public void downloadFile(@PathVariable Long projectId,
                             @PathVariable String viewType,
                             @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader,
                             HttpServletResponse response) throws Exception {
        StaffRole role = StaffRole.fromHeader(roleHeader);
        ImageArchiveService.DownloadTarget target = imageArchiveService.resolveForDownload(projectId, viewType);
        Map<String, String> metaHeaders = masker.downloadHeaders(
                target.getArchive(), target.getBlob(), role);
        for (Map.Entry<String, String> entry : metaHeaders.entrySet()) {
            response.setHeader(entry.getKey(), entry.getValue());
        }
        response.setContentType(target.getBlob().getContentType());
        response.setContentLengthLong(target.getBlob().getSizeBytes());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(target.getArchive().getFileName(), StandardCharsets.UTF_8)
                .build();
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());

        Path path = target.getPath();
        Files.copy(path, response.getOutputStream());
        response.getOutputStream().flush();
    }
}
