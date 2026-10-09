package com.laundry.machine.dto;

import com.laundry.machine.entity.MachineType;

public record MachineQrLabelDto(
        String machineCode,
        String displayName,
        MachineType type,
        String qrUrl
) {}
