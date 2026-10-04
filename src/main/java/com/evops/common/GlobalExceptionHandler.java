package com.evops.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务规则冲突：状态非法、编号重复等 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResponse.fail(ex.getMessage());
    }

    /** 请求体校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
        return ApiResponse.fail(firstFieldError(ex.getBindingResult().getFieldErrors()));
    }

    @ExceptionHandler(BindException.class)
    public ApiResponse<Void> handleBind(BindException ex) {
        return ApiResponse.fail(firstFieldError(ex.getBindingResult().getFieldErrors()));
    }

    /** 数据库唯一约束兜底（编号唯一的最后一道防线） */
    @ExceptionHandler(DuplicateKeyException.class)
    public ApiResponse<Void> handleDuplicateKey(DuplicateKeyException ex) {
        log.warn("唯一约束冲突: {}", ex.getMostSpecificCause().getMessage());
        return ApiResponse.fail("编号冲突：文物编号或项目编号已存在");
    }

    /** 上传影像超过 multipart 大小上限（异常在进入 Controller 前抛出） */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ApiResponse<Void> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        return ApiResponse.fail("上传失败：影像或批次大小超过系统上限");
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception ex) {
        log.error("未预期异常", ex);
        return ApiResponse.fail("系统处理失败");
    }

    private String firstFieldError(java.util.List<FieldError> errors) {
        if (errors == null || errors.isEmpty()) {
            return "请求参数不合法";
        }
        return errors.stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
    }
}
