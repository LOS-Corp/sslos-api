package com.laundry.transaction.dto.response;

import com.laundry.transaction.entity.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for transaction
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponse {

    private UUID transactionId;
    private UUID customerId;
    private ServiceType serviceType;
    private TransactionStatus status;
    private BigDecimal totalAmount;
    private LocalDateTime expiresAt;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;

    // For SELF_SERVICE
    private MachineReservationInfo machineReservation;

    // For LAUNDRY_SERVICE
    private LaundryOrderInfo laundryOrder;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MachineReservationInfo {
        private UUID reservationId;
        private UUID machineId;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private ReservationStatus status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LaundryOrderInfo {
        private UUID orderId;
        private String customerName;
        private String customerPhone;
        private BigDecimal weightKg;
        private LaundryOrderStatus status;
        private DropOffLockerInfo dropOffLocker;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DropOffLockerInfo {
        private UUID reservationId;
        private UUID lockerId;
        private LockerReservationStatus status;
    }
}
