package com.evops.constant;

import java.util.Arrays;

/**
 * 影像视角：正面 / 背面 / 底部 / 细部。库中存英文码值。
 */
public enum ViewType {

    FRONT("正面"),
    BACK("背面"),
    BOTTOM("底部"),
    DETAIL("细部");

    private final String label;

    ViewType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 严格解析：非法视角返回 null，由调用方组装逐文件失败原因 */
    public static ViewType fromCode(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim().toUpperCase();
        return Arrays.stream(values())
                .filter(v -> v.name().equals(normalized))
                .findFirst().orElse(null);
    }
}
