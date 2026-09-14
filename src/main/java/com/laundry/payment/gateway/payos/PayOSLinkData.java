package com.laundry.payment.gateway.payos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * PayOS Link Data Response
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayOSLinkData {
    
    private String bin;
    private String accountNumber;
    private String accountName;
    private String bankName;
    private String paymentLinkId;
    private String checkoutUrl;
    private String qrCode;
    private List<Transaction> transactions;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Transaction {
        private String transactionId;
        private long amount;
        private long transferredAmount;
    }
}
