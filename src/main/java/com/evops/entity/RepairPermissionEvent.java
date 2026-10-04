package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 修复权限闸事件：只追加。每次收紧 / 放宽落一行，不做物理删除。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_repair_permission_event")
public class RepairPermissionEvent extends BaseEntity {

    /** TIGHTEN / RELAX */
    private String eventType;

    private String reason;

    private String operatorRole;

    private LocalDateTime occurredAt;
}
