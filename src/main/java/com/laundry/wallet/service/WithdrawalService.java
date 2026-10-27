package com.laundry.wallet.service;

import com.laundry.attachment.service.AttachmentService;
import com.laundry.attachment.service.AttachmentService.Purpose;
import com.laundry.audit.service.AuditAction;
import com.laundry.audit.service.AuditService;
import com.laundry.audit.service.AuditService.AuditEntry;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.shared.money.Money;
import com.laundry.shared.security.CurrentUser;
import com.laundry.wallet.dto.WalletDtos.WithdrawalDto;
import com.laundry.wallet.dto.WalletDtos.WithdrawalRequest;
import com.laundry.wallet.entity.WalletEntryType;
import com.laundry.wallet.entity.WalletWithdrawal;
import com.laundry.wallet.repository.WalletWithdrawalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * UC-13: the customer asks to withdraw wallet credit to a bank account; the amount is held at once
 * (balance can never be spent twice, BR-15). The Owner transfers manually within 3 working days (D-20)
 * and completes the request with the transfer proof, or rejects it with a reason, which releases the hold.
 */
@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private static final String ENTITY = "WALLET_WITHDRAWAL";

    private final WalletWithdrawalRepository withdrawals;
    private final WalletService wallet;
    private final AuditService audit;
    private final Clock clock;
    private final AttachmentService attachments;

    @Transactional
    public WithdrawalDto request(CurrentUser customer, WithdrawalRequest request) {
        Money amount = Money.vnd(request.amountVnd());
        if (wallet.balanceOf(customer.id()).compareTo(amount) < 0) {
            throw BusinessException.of(ErrorCode.INSUFFICIENT_BALANCE, "The amount exceeds the wallet balance");
        }
        WalletWithdrawal withdrawal = withdrawals.save(WalletWithdrawal.request(customer.id(), amount.vnd(),
                request.bankName().trim(), request.accountNumber().replaceAll("\\s", ""),
                request.accountHolder().trim(), OffsetDateTime.now(clock)));
        // The debit re-checks the balance under the wallet row lock; a concurrent spend fails here.
        wallet.debit(customer.id(), amount, WalletEntryType.WITHDRAWAL_HOLD, "withdrawal:" + withdrawal.getId(),
                "Giữ tiền chờ chuyển khoản");
        audit.record(AuditEntry.of(AuditAction.WITHDRAWAL_REQUESTED, ENTITY, withdrawal.getId(), null, customer)
                .withChange(null, Map.of("amountVnd", amount.vnd())));
        return WithdrawalDto.from(withdrawal);
    }

    @Transactional
    public WithdrawalDto complete(UUID withdrawalId, String proof, CurrentUser owner) {
        attachments.requireReferencesValid(proof, Purpose.WITHDRAWAL_PROOF);
        WalletWithdrawal withdrawal = locked(withdrawalId);
        withdrawal.complete(owner.id(), proof, OffsetDateTime.now(clock));
        audit.record(AuditEntry.of(AuditAction.WITHDRAWAL_COMPLETED, ENTITY, withdrawal.getId(), null, owner)
                .withReason(withdrawal.getProofReference())
                .withChange(Map.of("status", "PROCESSING"), Map.of("status", "COMPLETED")));
        return WithdrawalDto.from(withdrawal);
    }

    @Transactional
    public WithdrawalDto reject(UUID withdrawalId, String reason, CurrentUser owner) {
        WalletWithdrawal withdrawal = locked(withdrawalId);
        withdrawal.reject(owner.id(), reason, OffsetDateTime.now(clock));
        wallet.credit(withdrawal.getCustomerId(), Money.vnd(withdrawal.getAmountVnd()),
                WalletEntryType.WITHDRAWAL_RELEASE, "withdrawal:" + withdrawal.getId(), "Hoàn tiền giữ do từ chối rút");
        audit.record(AuditEntry.of(AuditAction.WITHDRAWAL_REJECTED, ENTITY, withdrawal.getId(), null, owner)
                .withReason(withdrawal.getRejectionReason())
                .withChange(Map.of("status", "PROCESSING"), Map.of("status", "REJECTED")));
        return WithdrawalDto.from(withdrawal);
    }

    @Transactional(readOnly = true)
    public List<WithdrawalDto> mine(UUID customerId) {
        return withdrawals.findByCustomerIdOrderByRequestedAtDesc(customerId).stream().map(WithdrawalDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<WithdrawalDto> processing() {
        return withdrawals.findByStatusOrderByRequestedAtAsc(WalletWithdrawal.Status.PROCESSING).stream()
                .map(WithdrawalDto::from).toList();
    }

    private WalletWithdrawal locked(UUID withdrawalId) {
        return withdrawals.findByIdForUpdate(withdrawalId)
                .orElseThrow(() -> BusinessException.notFound("Withdrawal not found"));
    }
}
