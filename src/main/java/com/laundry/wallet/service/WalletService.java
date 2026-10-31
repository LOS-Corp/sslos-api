package com.laundry.wallet.service;

import com.laundry.shared.money.Money;
import com.laundry.wallet.dto.WalletDtos.WalletEntryDto;
import com.laundry.wallet.dto.WalletDtos.WalletView;
import com.laundry.wallet.entity.Wallet;
import com.laundry.wallet.entity.WalletEntry;
import com.laundry.wallet.entity.WalletEntryType;
import com.laundry.wallet.repository.WalletEntryRepository;
import com.laundry.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The credit wallet (FR-WAL-01…03, BR-15). Every money movement is one immutable ledger entry posted
 * in the caller's transaction ({@link Propagation#MANDATORY}), so a refund and its credit commit or roll
 * back together (FR-WAL-02). Posting the same (type, reference) twice is a no-op (NFR-06).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private static final int MAX_PAGE = 100;

    private final WalletRepository wallets;
    private final WalletEntryRepository entries;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void credit(UUID customerId, Money amount, WalletEntryType type, String reference, String description) {
        requirePositive(amount);
        post(customerId, amount, type, reference, description);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void debit(UUID customerId, Money amount, WalletEntryType type, String reference, String description) {
        requirePositive(amount);
        post(customerId, amount.negate(), type, reference, description);
    }

    @Transactional(readOnly = true)
    public Money balanceOf(UUID customerId) {
        return wallets.findByCustomerId(customerId).map(Wallet::balance).orElse(Money.ZERO);
    }

    @Transactional(readOnly = true)
    public WalletView view(UUID customerId, int page, int size) {
        return wallets.findByCustomerId(customerId)
                .map(wallet -> new WalletView(wallet.getBalanceVnd(),
                        entries.findByWalletIdOrderByCreatedAtDescIdDesc(wallet.getId(),
                                        PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE)))
                                .map(WalletEntryDto::from).getContent()))
                .orElse(new WalletView(0, List.of()));
    }

    /** NFR-17: wallets whose cached balance no longer equals the ledger sum (should always be empty). */
    @Transactional(readOnly = true)
    public List<UUID> outOfBalanceWallets() {
        return wallets.findOutOfBalanceWalletIds();
    }

    private void post(UUID customerId, Money amount, WalletEntryType type, String reference, String description) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Wallet wallet = wallets.findByCustomerIdForUpdate(customerId)
                .orElseGet(() -> wallets.saveAndFlush(Wallet.openFor(customerId, now)));
        if (entries.existsByWalletIdAndTypeAndReference(wallet.getId(), type, reference)) {
            log.info("Wallet entry {}:{} already posted; skipping", type, reference);
            return;
        }
        entries.save(WalletEntry.post(wallet, type, amount, reference, description, now));
    }

    private static void requirePositive(Money amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Wallet amounts must be positive");
        }
    }
}
