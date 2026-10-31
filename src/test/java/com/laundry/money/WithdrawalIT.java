package com.laundry.money;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.shared.money.Money;
import com.laundry.shared.security.CurrentUser;
import com.laundry.shared.security.Role;
import com.laundry.support.Fixtures;
import com.laundry.support.IntegrationTest;
import com.laundry.wallet.dto.WalletDtos.WithdrawalDto;
import com.laundry.wallet.dto.WalletDtos.WithdrawalRequest;
import com.laundry.wallet.entity.WalletEntryType;
import com.laundry.wallet.service.WalletService;
import com.laundry.wallet.service.WithdrawalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-13 / FR-WAL-03 / BR-15 / SC-30: withdrawals are held at once and completed only with proof. */
@IntegrationTest
@AutoConfigureMockMvc
class WithdrawalIT {

    @Autowired private WithdrawalService withdrawals;
    @Autowired private WalletService wallet;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;

    private CurrentUser customer;
    private CurrentUser owner;

    @BeforeEach
    void setUp() {
        customer = CurrentUser.of(Fixtures.customer(jdbc), Role.CUSTOMER);
        owner = CurrentUser.of(Fixtures.user(jdbc, "OWNER"), Role.OWNER);
        tx.executeWithoutResult(s -> wallet.credit(customer.id(), Money.vnd(35_000), WalletEntryType.REFUND_CREDIT,
                "seed:" + UUID.randomUUID(), "Seed"));
    }

    @Test
    @DisplayName("SC-30: withdraw 35,000 without proof → PROCESSING, balance held, not COMPLETED; with proof → COMPLETED")
    void heldThenCompletedWithProof() {
        WithdrawalDto requested = withdrawals.request(customer, request(35_000));

        assertThat(requested.status()).isEqualTo("PROCESSING");
        assertThat(requested.accountNumber()).as("NFR-19 masked").isEqualTo("******6789");
        assertThat(wallet.balanceOf(customer.id()).vnd()).isZero();
        assertThatThrownBy(() -> withdrawals.complete(requested.id(), " ", owner))
                .isInstanceOf(BusinessException.class);

        WithdrawalDto done = withdrawals.complete(requested.id(), "VCB FT26301123456", owner);
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(wallet.balanceOf(customer.id()).vnd()).isZero();
        assertThatThrownBy(() -> withdrawals.reject(requested.id(), "late", owner))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    @DisplayName("UC-13 4a: rejection with a reason releases the held amount; more than the balance is refused")
    void rejectReleasesTheHold() {
        assertThatThrownBy(() -> withdrawals.request(customer, request(40_000)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));

        WithdrawalDto requested = withdrawals.request(customer, request(20_000));
        assertThat(withdrawals.processing()).extracting(WithdrawalDto::id).contains(requested.id());
        assertThat(wallet.balanceOf(customer.id()).vnd()).isEqualTo(15_000);

        withdrawals.reject(requested.id(), "Sai tên chủ tài khoản", owner);
        assertThat(wallet.balanceOf(customer.id()).vnd()).isEqualTo(35_000);
        assertThat(withdrawals.mine(customer.id())).extracting(WithdrawalDto::status).containsExactly("REJECTED");
        assertThat(wallet.outOfBalanceWallets()).isEmpty();
    }

    @Test
    @DisplayName("Only the Owner sees and decides withdrawals; Staff and Admin cannot")
    void permissions() throws Exception {
        String anyId = UUID.randomUUID().toString();
        mvc.perform(get("/api/wallet/withdrawals").with(as(Role.STAFF))).andExpect(status().isForbidden());
        mvc.perform(get("/api/wallet/withdrawals").with(as(Role.CUSTOMER))).andExpect(status().isForbidden());
        mvc.perform(post("/api/wallet/withdrawals/" + anyId + "/complete").with(as(Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"proof\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/wallet/withdrawals").with(as(Role.OWNER))).andExpect(status().isOk());
        mvc.perform(get("/api/wallet/withdrawals/me").with(as(Role.CUSTOMER))).andExpect(status().isOk());
    }

    private static WithdrawalRequest request(long amount) {
        return new WithdrawalRequest(amount, "Vietcombank", "0123 456 789", "NGUYEN VAN A");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(Role role) {
        return authentication(new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), null,
                List.of(new SimpleGrantedAuthority(role.authority()))));
    }
}
