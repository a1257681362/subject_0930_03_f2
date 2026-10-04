package com.evops.dto.image;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class ImageVoidRequest {

    @NotBlank(message = "作废原因不能为空")
    private String reason;
}
