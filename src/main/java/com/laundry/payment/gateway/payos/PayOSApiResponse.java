package com.laundry.payment.gateway.payos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PayOS API Response
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayOSApiResponse<T> {
    
    private int code;
    private String desc;
    private DataResponse<T> data;
    private String signature;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DataResponse<T> {
        private int code;
        private String desc;
        private T data;
    }
}
