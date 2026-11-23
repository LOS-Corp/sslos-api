package com.laundry.wallet;

import com.laundry.common.PageResponse;
import com.laundry.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

public final class WalletControllers {

    private WalletControllers() {
    }

    public record WalletView(long balance, long heldBalance) {
    }

    public record EntryView(UUID id, WalletTransaction.Type type, long amount, long heldDelta, long balanceAfter,
                            UUID refundId, UUID paymentId, UUID withdrawalId, String note, Instant createdAt) {
        static EntryView of(WalletTransaction t) {
            return new EntryView(t.getId(), t.getEntryType(), t.getAmount(), t.getHeldDelta(), t.getBalanceAfter(),
                    t.getRefundId(), t.getPaymentId(), t.getWithdrawalId(), t.getNote(), t.getCreatedAt());
        }
    }

    public record WithdrawalView(UUID id, UUID userId, long amount, String bankBin, String bankName,
                                 String accountNumber, String accountName, WalletWithdrawal.Status status,
                                 Instant requestedAt, Instant dueAt, Instant processedAt, String transferReference,
                                 UUID proofAttachmentId, String rejectReason) {
        static WithdrawalView of(WalletWithdrawal w) {
            return new WithdrawalView(w.getId(), w.getUserId(), w.getAmount(), w.getBankBin(), w.getBankName(),
                    w.getBankAccountNumber(), w.getBankAccountName(), w.getStatus(), w.getRequestedAt(), w.getDueAt(),
                    w.getProcessedAt(), w.getTransferReference(), w.getProofAttachmentId(), w.getRejectReason());
        }
    }

    public record WithdrawBody(@Min(10_000) long amount,
                               @NotBlank @Size(max = 10) String bankBin,
                               @Size(max = 120) String bankName,
                               @NotBlank @Size(max = 30) String accountNumber,
                               @NotBlank @Size(max = 120) String accountName) {
    }

    public record CompleteBody(@NotNull UUID proofAttachmentId, @Size(max = 100) String transferReference) {
    }

    public record RejectBody(@NotBlank String reason) {
    }

    @RestController
    @RequiredArgsConstructor
    @Tag(name = "Ví của tôi")
    public static class MyWalletController {
        private final WalletService wallets;
        private final WalletTransactionRepository ledger;
        private final WithdrawalService withdrawals;

        @GetMapping("/api/wallet")
        public WalletView wallet() {
            Wallet w = wallets.get(Actor.userId());
            return new WalletView(w.getBalance(), w.getHeldBalance());
        }

        @GetMapping("/api/wallet/transactions")
        public PageResponse<EntryView> transactions(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
            return PageResponse.of(ledger.findByUserIdOrderByCreatedAtDesc(Actor.userId(),
                    PageRequest.of(page, Math.min(size, 100))), EntryView::of);
        }

        @GetMapping("/api/wallet/withdrawals")
        public PageResponse<WithdrawalView> myWithdrawals(@RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
            return PageResponse.of(withdrawals.mine(Actor.userId(), page, size), WithdrawalView::of);
        }

        @PostMapping("/api/wallet/withdrawals")
        @ResponseStatus(HttpStatus.CREATED)
        @Operation(summary = "Yêu cầu rút ví về ngân hàng; số tiền bị giữ ngay")
        public WithdrawalView withdraw(@Valid @RequestBody WithdrawBody body) {
            return WithdrawalView.of(withdrawals.request(Actor.userId(), new WithdrawalService.Request(body.amount(),
                    body.bankBin(), body.bankName(), body.accountNumber(), body.accountName())));
        }

        @PostMapping("/api/wallet/withdrawals/{id}/cancel")
        public WithdrawalView cancel(@PathVariable UUID id) {
            return WithdrawalView.of(withdrawals.cancel(Actor.userId(), id));
        }
    }

    @RestController
    @RequiredArgsConstructor
    @Tag(name = "Chủ cửa hàng · Rút ví")
    public static class OwnerWithdrawalController {
        private final WithdrawalService withdrawals;

        @GetMapping("/api/owner/withdrawals")
        public PageResponse<WithdrawalView> list(@RequestParam(defaultValue = "PENDING") WalletWithdrawal.Status status,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "50") int size) {
            return PageResponse.of(withdrawals.byStatus(status, page, size), WithdrawalView::of);
        }

        @PostMapping("/api/owner/withdrawals/{id}/complete")
        @Operation(summary = "Đã chuyển khoản: tải chứng từ (ownerType WITHDRAWAL) trước rồi gửi id của nó")
        public WithdrawalView complete(@PathVariable UUID id, @Valid @RequestBody CompleteBody body) {
            return WithdrawalView.of(withdrawals.complete(id, body.proofAttachmentId(), body.transferReference()));
        }

        @PostMapping("/api/owner/withdrawals/{id}/reject")
        public WithdrawalView reject(@PathVariable UUID id, @Valid @RequestBody RejectBody body) {
            return WithdrawalView.of(withdrawals.reject(id, body.reason()));
        }
    }
}
