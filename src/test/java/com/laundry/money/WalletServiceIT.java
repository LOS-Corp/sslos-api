package com.laundry.money;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.shared.money.Money;
import com.laundry.support.Fixtures;
import com.laundry.support.IntegrationTest;
import com.laundry.wallet.dto.WalletDtos.WalletView;
import com.laundry.wallet.entity.WalletEntryType;
import com.laundry.wallet.service.WalletReconciliationJob;
import com.laundry.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-WAL-01, BR-15: one wallet per customer, immutable ledger, never negative, idempotent postings. */
@IntegrationTest
class WalletServiceIT {

    @Autowired private WalletService wallet;
    @Autowired private WalletReconciliationJob reconciliation;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID customer;

    @BeforeEach
    void setUp() {
        customer = Fixtures.customer(jdbc);
    }

    @Test
    @DisplayName("Credit, debit and ledger view; the same event is never posted twice")
    void ledger() {
        tx.executeWithoutResult(s -> {
            wallet.credit(customer, Money.vnd(50_000), WalletEntryType.REFUND_CREDIT, "refund:a", "Hoàn tiền");
            wallet.credit(customer, Money.vnd(50_000), WalletEntryType.REFUND_CREDIT, "refund:a", "Gửi lại");
            wallet.debit(customer, Money.vnd(20_000), WalletEntryType.PAYMENT_DEBIT, "payment:b", "Thanh toán");
        });

        WalletView view = wallet.view(customer, 0, 10);
        assertThat(view.balanceVnd()).isEqualTo(30_000);
        assertThat(view.entries()).extracting(e -> e.amountVnd()).containsExactly(-20_000L, 50_000L);
        assertThat(wallet.view(UUID.randomUUID(), 0, 10).balanceVnd()).as("no wallet yet").isZero();
    }

    @Test
    @DisplayName("BR-15: the balance can never go negative")
    void neverNegative() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                wallet.debit(customer, Money.vnd(1), WalletEntryType.PAYMENT_DEBIT, "payment:x", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));
        assertThat(wallet.balanceOf(customer)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("FR-WAL-02: wallet postings must join the business transaction; zero or negative amounts are refused")
    void postingRules() {
        assertThatThrownBy(() -> wallet.credit(customer, Money.vnd(1), WalletEntryType.REFUND_CREDIT, "r:1", null))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                wallet.credit(customer, Money.ZERO, WalletEntryType.REFUND_CREDIT, "r:2", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("NFR-17: the reconciliation job finds no difference between balances and ledgers")
    void reconciliation() {
        tx.executeWithoutResult(s ->
                wallet.credit(customer, Money.vnd(10_000), WalletEntryType.REFUND_CREDIT, "refund:r", null));
        assertThat(wallet.outOfBalanceWallets()).isEmpty();
        assertThatCode(reconciliation::reconcile).doesNotThrowAnyException();
    }
}
