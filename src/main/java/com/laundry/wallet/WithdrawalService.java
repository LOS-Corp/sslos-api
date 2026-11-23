package com.laundry.wallet;

import com.laundry.attachment.AttachmentService;
import com.laundry.audit.AuditService;
import com.laundry.common.Reasons;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.security.Actor;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

/** Rút ví (§15.2): giữ tiền ngay; chủ cửa hàng chuyển khoản trong 3 ngày làm việc, đính chứng từ mới hoàn tất. */
@Service
@RequiredArgsConstructor
public class WithdrawalService {

    static final int BUSINESS_DAYS = 3;
    static final long MIN_AMOUNT = 10_000;
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final WalletWithdrawalRepository withdrawals;
    private final WalletService wallets;
    private final AttachmentService attachments;
    private final AuditService audit;
    private final Clock clock;

    public record Request(long amount, String bankBin, String bankName, String accountNumber, String accountName) {
    }

    @Transactional
    public WalletWithdrawal request(UUID userId, Request r) {
        if (r.amount() < MIN_AMOUNT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Số tiền rút tối thiểu là 10.000 đ");
        }
        Instant now = clock.instant();
        WalletWithdrawal w = new WalletWithdrawal();
        w.setUserId(userId);
        w.setAmount(r.amount());
        w.setBankBin(r.bankBin().trim());
        w.setBankName(r.bankName());
        w.setBankAccountNumber(r.accountNumber().replaceAll("\\s", ""));
        w.setBankAccountName(r.accountName().trim().toUpperCase());
        w.setRequestedAt(now);
        w.setDueAt(addBusinessDays(now, BUSINESS_DAYS));
        withdrawals.saveAndFlush(w);
        wallets.holdForWithdrawal(userId, r.amount(), w.getId());
        return w;
    }

    /** Khách hủy khi chủ cửa hàng chưa xử lý: trả tiền giữ về ví. */
    @Transactional
    public WalletWithdrawal cancel(UUID userId, UUID id) {
        WalletWithdrawal w = locked(id);
        if (!w.getUserId().equals(userId)) throw BusinessException.notFound("yêu cầu rút");
        requirePending(w);
        w.setStatus(WalletWithdrawal.Status.CANCELLED);
        w.setProcessedAt(clock.instant());
        wallets.releaseWithdrawal(userId, w.getAmount(), id);
        return w;
    }

    @Transactional
    public WalletWithdrawal complete(UUID id, UUID proofAttachmentId, String transferReference) {
        WalletWithdrawal w = locked(id);
        requirePending(w);
        attachments.requireOwned(proofAttachmentId, "WITHDRAWAL", id);
        w.setStatus(WalletWithdrawal.Status.COMPLETED);
        w.setProofAttachmentId(proofAttachmentId);
        w.setTransferReference(transferReference);
        w.setProcessedBy(Actor.userId());
        w.setProcessedAt(clock.instant());
        wallets.payoutWithdrawal(w.getUserId(), w.getAmount(), id);
        audit.user("WITHDRAWAL_COMPLETED", "wallet_withdrawal", id.toString(),
                "Đã chuyển khoản " + w.getAmount() + " đ", null,
                Map.of("amount", w.getAmount(), "reference", String.valueOf(transferReference)));
        return w;
    }

    @Transactional
    public WalletWithdrawal reject(UUID id, String reason) {
        WalletWithdrawal w = locked(id);
        requirePending(w);
        String why = Reasons.require(reason);
        w.setStatus(WalletWithdrawal.Status.REJECTED);
        w.setRejectReason(why);
        w.setProcessedBy(Actor.userId());
        w.setProcessedAt(clock.instant());
        wallets.releaseWithdrawal(w.getUserId(), w.getAmount(), id);
        audit.user("WITHDRAWAL_REJECTED", "wallet_withdrawal", id.toString(), why, null, Map.of("amount", w.getAmount()));
        return w;
    }

    public Page<WalletWithdrawal> mine(UUID userId, int page, int size) {
        return withdrawals.findByUserIdOrderByRequestedAtDesc(userId, PageRequest.of(page, Math.min(size, 100)));
    }

    public Page<WalletWithdrawal> byStatus(WalletWithdrawal.Status status, int page, int size) {
        return withdrawals.findByStatusOrderByDueAtAsc(status, PageRequest.of(page, Math.min(size, 100)));
    }

    private WalletWithdrawal locked(UUID id) {
        return withdrawals.lock(id).orElseThrow(() -> BusinessException.notFound("yêu cầu rút"));
    }

    private static void requirePending(WalletWithdrawal w) {
        if (w.getStatus() != WalletWithdrawal.Status.PENDING) {
            throw new BusinessException(ErrorCode.ORDER_STATE, "Yêu cầu rút đã được xử lý");
        }
    }

    static Instant addBusinessDays(Instant from, int days) {
        ZonedDateTime t = from.atZone(ZONE);
        int added = 0;
        while (added < days) {
            t = t.plusDays(1);
            if (t.getDayOfWeek() != DayOfWeek.SATURDAY && t.getDayOfWeek() != DayOfWeek.SUNDAY) added++;
        }
        return t.toInstant();
    }
}
