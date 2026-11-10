package com.laundry.wallet.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * NFR-17: once a day, check that every wallet's stored balance equals the sum of its ledger entries.
 * A difference is only logged for the Owner to investigate; it is never fixed automatically.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletReconciliationJob {

    private final WalletService walletService;

    @Scheduled(cron = "${app.wallet.reconciliation-cron:0 30 2 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void reconcile() {
        List<UUID> broken = walletService.outOfBalanceWallets();
        if (broken.isEmpty()) {
            log.info("Wallet reconciliation: every balance matches its ledger");
        } else {
            log.error("Wallet reconciliation: {} wallet(s) differ from their ledger: {}", broken.size(), broken);
        }
    }
}
