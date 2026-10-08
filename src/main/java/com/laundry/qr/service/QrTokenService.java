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
 * Manages QR tokens for locker operations only:
 * - LOCKER_DROP_OFF: Drop off laundry at locker
 * - LOCKER_PICKUP: Pick up laundry from locker
 *
 * <p>Machine QR codes are now STATIC and do NOT use tokens:
 * - Static QR: {@code GET /api/machines/{machineCode}/qr}
 * - Encodes: {@code <qrBaseUrl>/machines/<machineCode>}
 * - No expiry, no token, deterministic — print once, valid forever
 * - Scanning the QR lands on the machine page; user must still authenticate
 *   and have a valid booking to issue a START command
 *
 * <p>The old MACHINE_ACTIVATION token flow is deprecated and no longer created.
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

    // ── Machine activation tokens removed ────────────────────────────────────
    // MACHINE_ACTIVATION is replaced by static QR from GET /api/machines/{machineCode}/qr
    // See QrTokenController.getTransactionQr() javadoc for migration path.

    // ── Locker token helpers ────────────────────────────────────────────────
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
     * Scheduled job to expire old locker/pickup tokens.
     *
     * <p>Scheduling: every {@code app.qr.expiration-interval-ms} (default 60000 = 1 min).
     *
     * <p>CRITICAL — static machine QR codes do NOT use this table:
     * <ul>
     *   <li>{@code MACHINE_ACTIVATION} tokens are NOT created by the backend anymore.
     *       The QR for a machine is a deterministic URL returned by
     *       {@code GET /api/machines/{machineCode}/qr} with no expiry.</li>
     *   <li>Only {@code LOCKER_DROP_OFF} and {@code LOCKER_PICKUP} tokens are
     *       touched by this scheduler (see {@link QrTokenRepository#expireLockerTokens}).</li>
     *   <li>If any historical MACHINE_ACTIVATION rows are still in the DB, they are
     *       left alone (status, expiresAt) — they will simply be ignored by the
     *       application, which is the desired behavior.</li>
     * </ul>
     *
     * <p>The job is silent when there is nothing to expire — was the source of
     * repetitive INFO log noise on startup.
     */
    @Scheduled(fixedDelayString = "${app.qr.expiration-interval-ms:60000}")
    @Transactional
    public void expireOldLockerTokens() {
        LocalDateTime now = LocalDateTime.now();
        int expired = qrTokenRepository.expireLockerTokens(now);
        if (expired > 0) {
            log.info("Expired {} locker/pickup QR tokens", expired);
        }
    }

    /**
     * Legacy entry point — retained so callers that wired against {@code expireOldTokens()}
     * (the previous name) keep working. Delegates to the new method.
     */
    @Deprecated
    public void expireOldTokens() {
        expireOldLockerTokens();
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
