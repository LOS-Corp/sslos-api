package com.laundry.machine.dto;

import com.laundry.machine.entity.DeviceEventType;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** DTO for device event responses. */
@Data
@Builder
public class DeviceEventDto {
    private UUID id;
    private UUID machineId;
    private String machineCode;
    private DeviceEventType eventType;
    private String detail;
    private UUID bookingId;
    private OffsetDateTime createdAt;
}
