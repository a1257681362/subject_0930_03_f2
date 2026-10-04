package com.evops.constant;

import java.util.Arrays;

/**
 * 影像相关岗位，由请求头 {@code X-Staff-Role} 声明。
 * 缺省或无法识别一律按最严格的 VIEWER 处理。
 *
 * 脱敏矩阵（版本/批次 JSON 与下载响应头共用同一遮蔽器）：
 * <pre>
 * 字段        RESTORER  ARCHIVIST  CURATOR  VIEWER
 * 摄影者      完整      完整       完整     遮蔽
 * 拍摄时间    完整时刻  完整时刻   仅日期   仅日期
 * 相机机型    可见      可见       遮蔽     遮蔽
 * 内容指纹    可见      可见       可见     遮蔽
 * 图像字节    可下载    可下载     可下载   可下载（字节不脱敏）
 * </pre>
 */
public enum StaffRole {

    /** 修复师：可上传、可下载，见完整元数据 */
    RESTORER(true, false, false, true, ShotPrecision.FULL, true, true),

    /** 档案员：可上传、可作废，见完整元数据 */
    ARCHIVIST(true, true, false, true, ShotPrecision.FULL, true, true),

    /** 业务主管：可作废、可收紧/放宽权限；拍摄时间仅见日期，不见机型 */
    CURATOR(false, true, true, true, ShotPrecision.DATE, false, true),

    /** 普通查看：仅可下载图像，摄影者/机型/指纹/具体时刻均遮蔽（最严格，也是缺省值） */
    VIEWER(false, false, false, false, ShotPrecision.DATE, false, false);

    /** 拍摄时间可见精度 */
    public enum ShotPrecision { FULL, DATE }

    private final boolean uploadAllowed;
    private final boolean voidAllowed;
    private final boolean permissionControlAllowed;
    private final boolean photographerVisible;
    private final ShotPrecision shotPrecision;
    private final boolean cameraVisible;
    private final boolean fingerprintVisible;

    StaffRole(boolean uploadAllowed, boolean voidAllowed, boolean permissionControlAllowed,
              boolean photographerVisible, ShotPrecision shotPrecision,
              boolean cameraVisible, boolean fingerprintVisible) {
        this.uploadAllowed = uploadAllowed;
        this.voidAllowed = voidAllowed;
        this.permissionControlAllowed = permissionControlAllowed;
        this.photographerVisible = photographerVisible;
        this.shotPrecision = shotPrecision;
        this.cameraVisible = cameraVisible;
        this.fingerprintVisible = fingerprintVisible;
    }

    public boolean canUpload() { return uploadAllowed; }
    public boolean canVoid() { return voidAllowed; }
    public boolean canControlPermission() { return permissionControlAllowed; }
    public boolean canSeePhotographer() { return photographerVisible; }
    public ShotPrecision getShotPrecision() { return shotPrecision; }
    public boolean canSeeCamera() { return cameraVisible; }
    public boolean canSeeFingerprint() { return fingerprintVisible; }

    /** 缺省/未知岗位按 VIEWER（最严格） */
    public static StaffRole fromHeader(String header) {
        if (header == null || header.trim().isEmpty()) {
            return VIEWER;
        }
        return Arrays.stream(values())
                .filter(r -> r.name().equalsIgnoreCase(header.trim()))
                .findFirst().orElse(VIEWER);
    }
}
