package com.laundry.wallet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Mỗi đêm so số dư ví với tổng sổ cái (§15.2). Lệch thì ghi wallet_reconciliations để chủ cửa hàng xem. */
@Component
@ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class WalletReconciliationJob {

    private final WalletRepository wallets;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Scheduled(cron = "0 15 2 * * *", zone = "Asia/Ho_Chi_Minh")
    @Transactional
    public void run() {
        LocalDate day = LocalDate.now(clock.withZone(ZoneId.of("Asia/Ho_Chi_Minh")));
        List<WalletRepository.Mismatch> mismatches = wallets.mismatches();
        long checked = wallets.count();
        String json = mapper.writeValueAsString(mismatches.stream().map(m -> java.util.Map.of(
                "userId", m.getUserId(), "balance", m.getBalance(), "held", m.getHeld(),
                "ledgerBalance", m.getLedgerBalance(), "ledgerHeld", m.getLedgerHeld())).toList());
        jdbc.update("""
                insert into wallet_reconciliations (run_date, finished_at, wallets_checked, mismatch_count, mismatches)
                values (?, now(), ?, ?, cast(? as jsonb))
                on conflict (run_date) do update set finished_at = now(), wallets_checked = excluded.wallets_checked,
                    mismatch_count = excluded.mismatch_count, mismatches = excluded.mismatches
                """, day, checked, mismatches.size(), json);
        if (!mismatches.isEmpty()) log.error("Wallet reconciliation {}: {} wallet(s) differ from the ledger", day, mismatches.size());
    }
}
