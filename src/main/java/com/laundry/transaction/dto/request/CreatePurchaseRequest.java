package com.laundry.transaction.dto.request;

import com.laundry.transaction.entity.ServiceType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request DTO for creating a purchase transaction
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePurchaseRequest {

    @NotNull(message = "Customer ID is required")
    private UUID customerId;

    @NotNull(message = "Service type is required")
    private ServiceType serviceType;

    // For SELF_SERVICE
    private UUID machineId;

    // For LAUNDRY_SERVICE
    private String customerName;
    private String customerPhone;
    private String serviceNotes;

    @Positive(message = "Duration must be positive")
    private Integer durationMinutes;

    private BigDecimal estimatedWeight;
}
