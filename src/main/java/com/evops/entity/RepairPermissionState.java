package com.evops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 修复权限闸全局单例状态，固定 id=1（CHECK 约束保证）。
 * 由服务层惰性建行：不能放进 schema.sql 的 MERGE，否则每次启动会把
 * 运行期收紧状态覆盖回初值。
 */
@Data
@TableName("t_repair_permission_state")
public class RepairPermissionState {

    @TableId(type = IdType.INPUT)
    private Integer id;

    /** TRUE 影像可上传；FALSE 权限收紧、上传停止推进 */
    private Boolean imageUploadOpen;

    private String currentReason;

    private LocalDateTime changedAt;

    private Long createBy;

    private LocalDateTime createTime;

    private Long updateBy;

    private LocalDateTime updateTime;
}
