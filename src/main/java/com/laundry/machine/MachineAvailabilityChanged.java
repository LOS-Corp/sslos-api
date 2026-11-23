package com.laundry.machine;

import java.util.UUID;

/**
 * Máy chuyển sang Lỗi / Bảo trì / Ngừng dùng / Mất kết nối (hoặc trở lại dùng được).
 * Module đặt lượt nghe sự kiện này để chuyển đơn sang máy khác hoặc hoàn tiền (§12, §16).
 */
public record MachineAvailabilityChanged(UUID machineId, boolean available, String reason) {
}
