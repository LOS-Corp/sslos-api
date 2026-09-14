package com.laundry.qr.service;

import com.laundry.qr.dto.response.QrTokenResponse;
import com.laundry.qr.entity.*;
import com.laundry.qr.exception.QrTokenException;
import com.laundry.qr.repository.QrTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * QR Token Service
 * 
 * Manages QR tokens for:
 * - MACHINE_ACTIVATION: Activate self-service machines
 * - LOCKER_DROP_OFF: Drop off laundry at locker
 * - LOCKER_PICKUP: Pick up laundry from locker
 * 
 * This service is exposed to Backend 2 for machine/locker operations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QrTokenService {

    private final QrTokenRepository qrTokenRepository;
    
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_LENGTH = 32;

    /**
     * Generate a secure token
     */
    private String generateSecureToken() {
        byte[] bytes = new byte[TOKEN_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Create a new QR token
     */
    @Transactional
    public QrTokenResponse createToken(QrTokenType type, UUID referenceId, String referenceType, 
                                       int expirationMinutes) {
        log.info("Creating QR token: type={}, referenceId={}, referenceType={}", type, referenceId, referenceType);
        
        // Generate unique token
        String token;
        do {
            token = generateSecureToken();
        } while (qrTokenRepository.findByToken(token).isPresent());

        QrToken qrToken = QrToken.builder()
            .token(token)
            .type(type)
            .referenceId(referenceId)
            .referenceType(referenceType)
            .status(QrTokenStatus.ACTIVE)
            .expiresAt(LocalDateTime.now().plusMinutes(expirationMinutes))
            .build();

        qrToken = qrTokenRepository.save(qrToken);
        
        return toResponse(qrToken);
    }

    /**
     * Create MACHINE_ACTIVATION token
     */
    public QrTokenResponse createMachineActivationToken(UUID reservationId, int expirationMinutes) {
        return createToken(QrTokenType.MACHINE_ACTIVATION, reservationId, "MACHINE_RESERVATION", expirationMinutes);
    }

    /**
     * Create LOCKER_DROP_OFF token
     */
    public QrTokenResponse createDropOffToken(UUID reservationId, int expirationMinutes) {
        return createToken(QrTokenType.LOCKER_DROP_OFF, reservationId, "LOCKER_RESERVATION", expirationMinutes);
    }

    /**
     * Create LOCKER_PICKUP token
     */
    public QrTokenResponse createPickupToken(UUID orderId, int expirationMinutes) {
        return createToken(QrTokenType.LOCKER_PICKUP, orderId, "LAUNDRY_ORDER", expirationMinutes);
    }

    /**
     * Validate a token - used by Backend 2
     */
    public QrTokenResponse validateToken(String token) {
        log.info("Validating QR token: {}", token);
        
        QrToken qrToken = qrTokenRepository.findByToken(token)
            .orElseThrow(() -> new QrTokenException("Token not found"));

        if (qrToken.getStatus() != QrTokenStatus.ACTIVE) {
            throw new QrTokenException("Token is not active: " + qrToken.getStatus());
        }

        if (qrToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            qrToken.markAsExpired();
            qrTokenRepository.save(qrToken);
            throw new QrTokenException("Token has expired");
        }

        return toResponse(qrToken);
    }

    /**
     * Mark token as used - called when operation completes
     */
    @Transactional
    public QrTokenResponse markAsUsed(String token, String usedBy) {
        log.info("Marking QR token as used: token={}, usedBy={}", token, usedBy);
        
        QrToken qrToken = qrTokenRepository.findByToken(token)
            .orElseThrow(() -> new QrTokenException("Token not found"));

        if (qrToken.getStatus() != QrTokenStatus.ACTIVE) {
            throw new QrTokenException("Token is not active: " + qrToken.getStatus());
        }

        if (qrToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            qrToken.markAsExpired();
            qrTokenRepository.save(qrToken);
            throw new QrTokenException("Token has expired");
        }

        qrToken.markAsUsed(usedBy);
        qrToken = qrTokenRepository.save(qrToken);

        return toResponse(qrToken);
    }

    /**
     * Revoke a token
     */
    @Transactional
    public void revokeToken(String token, String reason) {
        log.info("Revoking QR token: token={}, reason={}", token, reason);
        
        QrToken qrToken = qrTokenRepository.findByToken(token)
            .orElseThrow(() -> new QrTokenException("Token not found"));

        qrToken.revoke(reason);
        qrTokenRepository.save(qrToken);
    }

    /**
     * Get token by token string
     */
    public QrTokenResponse getByToken(String token) {
        QrToken qrToken = qrTokenRepository.findByToken(token)
            .orElseThrow(() -> new QrTokenException("Token not found"));
        return toResponse(qrToken);
    }

    /**
     * Scheduled job to expire old tokens
     */
    @Scheduled(fixedRate = 60000)
    @Transactional
    public void expireOldTokens() {
        LocalDateTime now = LocalDateTime.now();
        int expired = qrTokenRepository.expireOldTokens(now);
        if (expired > 0) {
            log.info("Expired {} QR tokens", expired);
        }
    }

    /**
     * Convert entity to response
     */
    private QrTokenResponse toResponse(QrToken qrToken) {
        return QrTokenResponse.builder()
            .id(qrToken.getId())
            .token(qrToken.getToken())
            .type(qrToken.getType())
            .referenceId(qrToken.getReferenceId())
            .referenceType(qrToken.getReferenceType())
            .status(qrToken.getStatus())
            .issuedAt(qrToken.getIssuedAt())
            .expiresAt(qrToken.getExpiresAt())
            .usedAt(qrToken.getUsedAt())
            .usedBy(qrToken.getUsedBy())
            .qrCodeData(qrToken.getToken()) // Token is the QR data
            .build();
    }
}
