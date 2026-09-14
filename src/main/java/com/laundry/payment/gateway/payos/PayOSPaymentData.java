package com.laundry.payment.gateway.payos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PayOS Payment Data DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayOSPaymentData {
    
    private String orderCode;
    private long amount;
    private String description;
    private String returnUrl;
    private String cancelUrl;
    private String buyerName;
    private String buyerEmail;
    private String buyerPhone;
    private String expiredAt;
}
