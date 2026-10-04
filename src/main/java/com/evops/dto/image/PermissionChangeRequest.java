package com.evops.dto.image;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class PermissionChangeRequest {

    @NotBlank(message = "调整原因不能为空")
    private String reason;
}
