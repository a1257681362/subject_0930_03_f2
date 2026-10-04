package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.constant.StaffRole;
import com.evops.dto.image.PermissionChangeRequest;
import com.evops.dto.image.PermissionStateView;
import com.evops.entity.RepairPermissionEvent;
import com.evops.entity.RepairPermissionState;
import com.evops.service.RepairPermissionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 修复权限闸：博物馆文物修复权限收紧时影像上传停止推进；
 * 已确认的版本归档与原文件、下载能力不受影响。仅 CURATOR 可收紧/放宽。
 */
@RestController
@RequestMapping("/api/repair-permission")
public class RepairPermissionController {

    private static final String ROLE_HEADER = "X-Staff-Role";

    private final RepairPermissionService permissionService;

    public RepairPermissionController(RepairPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /** 查看当前闸状态与收紧/放宽事件流水 */
    @GetMapping
    public ApiResponse<PermissionStateView> state() {
        RepairPermissionState state = permissionService.getState();
        List<RepairPermissionEvent> events = permissionService.listEvents();
        PermissionStateView view = new PermissionStateView();
        view.setImageUploadOpen(state.getImageUploadOpen());
        view.setCurrentReason(state.getCurrentReason());
        view.setChangedAt(state.getChangedAt());
        for (RepairPermissionEvent event : events) {
            PermissionStateView.EventView item = new PermissionStateView.EventView();
            item.setEventType(event.getEventType());
            item.setReason(event.getReason());
            item.setOperatorRole(event.getOperatorRole());
            item.setOccurredAt(event.getOccurredAt());
            view.getEvents().add(item);
        }
        return ApiResponse.ok(view);
    }

    /** 收紧：影像上传停止推进 */
    @PostMapping("/tighten")
    public ApiResponse<RepairPermissionState> tighten(
            @Valid @RequestBody PermissionChangeRequest request,
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader) {
        return ApiResponse.ok(permissionService.tighten(
                request.getReason(), StaffRole.fromHeader(roleHeader)));
    }

    /** 放宽：影像上传恢复 */
    @PostMapping("/relax")
    public ApiResponse<RepairPermissionState> relax(
            @Valid @RequestBody PermissionChangeRequest request,
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader) {
        return ApiResponse.ok(permissionService.relax(
                request.getReason(), StaffRole.fromHeader(roleHeader)));
    }
}
