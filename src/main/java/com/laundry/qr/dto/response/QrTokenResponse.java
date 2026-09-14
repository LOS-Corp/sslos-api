package com.laundry.qr.dto.response;

import com.laundry.qr.entity.QrTokenStatus;
import com.laundry.qr.entity.QrTokenType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QR Token response DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QrTokenResponse {

    private UUID id;
    private String token;
    private QrTokenType type;
    private UUID referenceId;
    private String referenceType;
    private QrTokenStatus status;
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime usedAt;
    private String usedBy;
    
    // Full QR code data for scanning
    private String qrCodeData;
}
