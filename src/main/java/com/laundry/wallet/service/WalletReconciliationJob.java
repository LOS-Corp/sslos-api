package com.laundry.wallet.service;

import com.laundry.shared.scheduling.JobLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * NFR-17: once a day, every wallet's cached balance must equal the sum of its immutable ledger.
 * A difference is never "fixed" automatically — it is logged as an error for the Owner to investigate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletReconciliationJob {

    private final WalletService walletService;
    private final JobLock jobLock;

    @Scheduled(cron = "${app.wallet.reconciliation-cron:0 30 2 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void reconcile() {
        jobLock.runExclusive("wallet-reconciliation", () -> {
            List<UUID> broken = walletService.outOfBalanceWallets();
            if (broken.isEmpty()) {
                log.info("Wallet reconciliation: every balance matches its ledger");
            } else {
                log.error("Wallet reconciliation: {} wallet(s) differ from their ledger: {}", broken.size(), broken);
            }
        });
    }
}
