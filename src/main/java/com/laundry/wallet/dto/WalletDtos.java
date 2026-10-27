package com.laundry.wallet.dto;

import com.laundry.wallet.entity.WalletEntry;
import com.laundry.wallet.entity.WalletWithdrawal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class WalletDtos {

    private WalletDtos() {
    }

    /** Balance and the latest ledger lines of the caller's wallet (UC-13 step 1). */
    public record WalletView(long balanceVnd, List<WalletEntryDto> entries) {
    }

    public record WalletEntryDto(long id, String type, long amountVnd, String reference, String description,
                                 OffsetDateTime createdAt) {

        public static WalletEntryDto from(WalletEntry entry) {
            return new WalletEntryDto(entry.getId(), entry.getType().name(), entry.getAmountVnd(),
                    entry.getReference(), entry.getDescription(), entry.getCreatedAt());
        }
    }

    /** UC-13 step 2: bank details and amount (integer VND). */
    public record WithdrawalRequest(
            @Min(10_000) @Max(50_000_000) long amountVnd,
            @NotBlank @Size(max = 100) String bankName,
            @NotBlank @Pattern(regexp = "[0-9 ]{6,30}", message = "Số tài khoản chỉ gồm chữ số") String accountNumber,
            @NotBlank @Size(max = 120) String accountHolder) {
    }

    /** Owner decision: {@code proof} to complete (transfer reference or document link), {@code reason} to reject. */
    public record WithdrawalDecision(@Size(max = 300) String proof, @Size(max = 500) String reason) {
    }

    /** The account number is masked (NFR-19). */
    public record WithdrawalDto(UUID id, UUID customerId, long amountVnd, String bankName, String accountNumber,
                                String accountHolder, String status, String proofReference, String rejectionReason,
                                OffsetDateTime requestedAt, OffsetDateTime decidedAt) {

        public static WithdrawalDto from(WalletWithdrawal w) {
            return new WithdrawalDto(w.getId(), w.getCustomerId(), w.getAmountVnd(), w.getBankName(),
                    w.maskedAccountNumber(), w.getAccountHolder(), w.getStatus().name(), w.getProofReference(),
                    w.getRejectionReason(), w.getRequestedAt(), w.getDecidedAt());
        }
    }
}
