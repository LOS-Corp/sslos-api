package com.laundry.machine.dto;

import com.laundry.machine.entity.DeviceCommandStatus;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** DTO for command response returned to the API caller. */
@Data
@Builder
public class DeviceCommandDto {
    private UUID id;
    private String commandId;
    private UUID machineId;
    private String machineCode;
    private String command;
    private UUID bookingId;
    private DeviceCommandStatus status;
    private OffsetDateTime sentAt;
    private OffsetDateTime acknowledgedAt;
    private String ackResult;
    private String ackReason;
    private OffsetDateTime timeoutAt;
    private OffsetDateTime createdAt;
}
