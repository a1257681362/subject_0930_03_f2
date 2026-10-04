package com.evops.dto.image;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class PermissionStateView {

    private Boolean imageUploadOpen;

    private String currentReason;

    private LocalDateTime changedAt;

    private List<EventView> events = new ArrayList<>();

    @Data
    public static class EventView {
        private String eventType;
        private String reason;
        private String operatorRole;
        private LocalDateTime occurredAt;
    }
}
