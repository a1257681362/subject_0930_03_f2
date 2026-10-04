package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.constant.MuseumConstants;
import com.evops.constant.StaffRole;
import com.evops.entity.RepairPermissionEvent;
import com.evops.entity.RepairPermissionState;
import com.evops.mapper.RepairPermissionEventMapper;
import com.evops.mapper.RepairPermissionStateMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.evops.constant.MuseumConstants.*;

/**
 * 修复权限闸：博物馆文物修复权限收紧时，影像上传整体停止推进；
 * 已经确认归档的版本、原文件与下载能力不受影响（放宽后恢复）。
 *
 * 单例状态行（固定 id=1）由本服务惰性创建，绝不写进 schema.sql 的 MERGE，
 * 否则每次启动 spring.sql.init 会把运行期收紧状态覆盖回初值。
 */
@Service
public class RepairPermissionService {

    private final RepairPermissionStateMapper stateMapper;
    private final RepairPermissionEventMapper eventMapper;

    public RepairPermissionService(RepairPermissionStateMapper stateMapper,
                                   RepairPermissionEventMapper eventMapper) {
        this.stateMapper = stateMapper;
        this.eventMapper = eventMapper;
    }

    /** 读单例状态；不存在则幂等创建为「开放」 */
    @Transactional
    public RepairPermissionState getState() {
        RepairPermissionState state = stateMapper.selectById(MuseumConstants.PERMISSION_STATE_ID);
        if (state != null) {
            return state;
        }
        LocalDateTime now = LocalDateTime.now();
        RepairPermissionState created = new RepairPermissionState();
        created.setId(MuseumConstants.PERMISSION_STATE_ID);
        created.setImageUploadOpen(Boolean.TRUE);
        created.setCurrentReason(null);
        created.setChangedAt(now);
        created.setCreateTime(now);
        created.setUpdateTime(now);
        try {
            stateMapper.insert(created);
            return created;
        } catch (DuplicateKeyException e) {
            // 并发首调：另一请求已建行，回读
            return stateMapper.selectById(MuseumConstants.PERMISSION_STATE_ID);
        }
    }

    /** 上传链路唯一硬检查点：收紧时直接拒绝，且不产生任何批次/文件数据 */
    public void requireUploadOpen() {
        RepairPermissionState state = getState();
        if (!Boolean.TRUE.equals(state.getImageUploadOpen())) {
            String reason = state.getCurrentReason();
            throw new IllegalArgumentException("文物修复权限已收紧，影像上传停止推进"
                    + (reason == null || reason.isEmpty() ? "" : "：" + reason));
        }
    }

    @Transactional
    public RepairPermissionState tighten(String reason, StaffRole role) {
        requireControlRole(role);
        RepairPermissionState state = getState();
        if (!Boolean.TRUE.equals(state.getImageUploadOpen())) {
            throw new IllegalArgumentException("影像上传当前已处于收紧状态");
        }
        LocalDateTime now = LocalDateTime.now();
        state.setImageUploadOpen(Boolean.FALSE);
        state.setCurrentReason(reason);
        state.setChangedAt(now);
        stateMapper.updateById(state);
        recordEvent(PERMISSION_TIGHTEN, reason, role, now);
        return state;
    }

    @Transactional
    public RepairPermissionState relax(String reason, StaffRole role) {
        requireControlRole(role);
        RepairPermissionState state = getState();
        if (Boolean.TRUE.equals(state.getImageUploadOpen())) {
            throw new IllegalArgumentException("影像上传当前已处于开放状态");
        }
        LocalDateTime now = LocalDateTime.now();
        state.setImageUploadOpen(Boolean.TRUE);
        state.setCurrentReason(reason);
        state.setChangedAt(now);
        stateMapper.updateById(state);
        recordEvent(PERMISSION_RELAX, reason, role, now);
        return state;
    }

    public List<RepairPermissionEvent> listEvents() {
        return eventMapper.selectList(new LambdaQueryWrapper<RepairPermissionEvent>()
                .orderByAsc(RepairPermissionEvent::getOccurredAt)
                .orderByAsc(RepairPermissionEvent::getId));
    }

    private void requireControlRole(StaffRole role) {
        if (role == null || !role.canControlPermission()) {
            throw new IllegalArgumentException("仅业务主管（CURATOR）可调整影像上传权限");
        }
    }

    private void recordEvent(String eventType, String reason, StaffRole role, LocalDateTime now) {
        RepairPermissionEvent event = new RepairPermissionEvent();
        event.setEventType(eventType);
        event.setReason(reason);
        event.setOperatorRole(role.name());
        event.setOccurredAt(now);
        eventMapper.insert(event);
    }
}
