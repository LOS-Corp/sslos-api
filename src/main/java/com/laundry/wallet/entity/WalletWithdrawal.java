package com.laundry.wallet.entity;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A request to move wallet credit to a bank account (UC-13). PROCESSING → COMPLETED (with proof) or REJECTED. */
@Entity
@Table(name = "wallet_withdrawals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletWithdrawal {

    public enum Status { PROCESSING, COMPLETED, REJECTED }

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "amount_vnd", nullable = false, updatable = false)
    private long amountVnd;

    @Column(name = "bank_name", nullable = false, length = 100)
    private String bankName;

    @Column(name = "account_number", nullable = false, length = 30)
    private String accountNumber;

    @Column(name = "account_holder", nullable = false, length = 120)
    private String accountHolder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "proof_reference", length = 300)
    private String proofReference;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    public static WalletWithdrawal request(UUID customerId, long amountVnd, String bankName, String accountNumber,
                                           String accountHolder, OffsetDateTime now) {
        WalletWithdrawal withdrawal = new WalletWithdrawal();
        withdrawal.id = UUID.randomUUID();
        withdrawal.customerId = customerId;
        withdrawal.amountVnd = amountVnd;
        withdrawal.bankName = bankName;
        withdrawal.accountNumber = accountNumber;
        withdrawal.accountHolder = accountHolder;
        withdrawal.status = Status.PROCESSING;
        withdrawal.requestedAt = now;
        return withdrawal;
    }

    /** BR-15: completed only with the transfer proof. */
    public void complete(UUID ownerId, String proof, OffsetDateTime now) {
        requireProcessing();
        if (proof == null || proof.isBlank()) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED, "A transfer proof is required to complete a withdrawal");
        }
        status = Status.COMPLETED;
        proofReference = proof.trim();
        decide(ownerId, now);
    }

    public void reject(UUID ownerId, String reason, OffsetDateTime now) {
        requireProcessing();
        if (reason == null || reason.isBlank()) {
            throw BusinessException.of(ErrorCode.REASON_REQUIRED, "Please enter a reason for this action");
        }
        status = Status.REJECTED;
        rejectionReason = reason.trim();
        decide(ownerId, now);
    }

    /** NFR-19: only the last four digits of the account are shown. */
    public String maskedAccountNumber() {
        String digits = accountNumber.replaceAll("\\s", "");
        return digits.length() <= 4 ? "****" : "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
    }

    private void requireProcessing() {
        if (status != Status.PROCESSING) {
            throw BusinessException.of(ErrorCode.INVALID_STATE_TRANSITION, "This withdrawal has already been decided");
        }
    }

    private void decide(UUID ownerId, OffsetDateTime now) {
        decidedBy = ownerId;
        decidedAt = now;
    }
}
