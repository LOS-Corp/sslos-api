package com.laundry.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.shared.error.BusinessException;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Evidence files on PostgreSQL: type sniffing, append-only index, who may read, proof references (UC-13, BR-15). */
@IntegrationTest
@AutoConfigureMockMvc
class AttachmentIT {

    /** The 8-byte PNG signature plus a little body: enough for type detection. */
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private WalletService wallet;
    @Autowired private WithdrawalService withdrawals;

    private UUID owner;
    private UUID customer;

    @BeforeEach
    void setUp() {
        owner = Fixtures.user(jdbc, "OWNER");
        customer = Fixtures.customer(jdbc);
    }

    @Test
    @DisplayName("BR-15: the Owner completes a withdrawal with an uploaded proof; only that customer can open it")
    void withdrawalProofFile() throws Exception {
        String reference = upload(owner, Role.OWNER, "WITHDRAWAL_PROOF", "ủy nhiệm chi.png", PNG);
        UUID id = UUID.fromString(reference.substring("attachment:".length()));

        assertThat(jdbc.queryForObject("SELECT content_type FROM attachments WHERE id = ?", String.class, id))
                .as("type from the signature, not the client").isEqualTo("image/png");
        mvc.perform(get("/api/attachments/" + id).with(as(customer, Role.CUSTOMER))).andExpect(status().isNotFound());

        WithdrawalDto requested = requestWithdrawal();
        WithdrawalDto done = withdrawals.complete(requested.id(), reference + " · VCB FT2630112", CurrentUser.of(owner, Role.OWNER));
        assertThat(done.status()).isEqualTo("COMPLETED");

        mvc.perform(get("/api/attachments/" + id).with(as(customer, Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(PNG));
        mvc.perform(get("/api/attachments/" + id).with(as(Fixtures.customer(jdbc), Role.CUSTOMER)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/attachments/" + id).with(as(Fixtures.user(jdbc, "STAFF"), Role.STAFF)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("A proof must point at a real file uploaded for that purpose")
    void danglingReferenceIsRefused() throws Exception {
        WithdrawalDto requested = requestWithdrawal();
        CurrentUser theOwner = CurrentUser.of(owner, Role.OWNER);
        assertThatThrownBy(() -> withdrawals.complete(requested.id(), "attachment:" + UUID.randomUUID(), theOwner))
                .isInstanceOf(BusinessException.class);

        String evidence = upload(owner, Role.OWNER, "REFUND_EVIDENCE", "may-hong.png", PNG);
        assertThatThrownBy(() -> withdrawals.complete(requested.id(), evidence, theOwner))
                .as("refund evidence is not a transfer proof").isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Only JPEG/PNG/PDF; customers cannot upload; the index is append-only")
    void rejectsWrongFilesAndKeepsEvidence() throws Exception {
        mvc.perform(multipart("/api/attachments").file(new MockMultipartFile("file", "x.png", "image/png", "<script>".getBytes()))
                        .param("purpose", "REFUND_EVIDENCE").with(as(owner, Role.OWNER)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("FILE_REJECTED"));
        mvc.perform(multipart("/api/attachments").file(new MockMultipartFile("file", "a.png", "image/png", PNG))
                        .param("purpose", "REFUND_EVIDENCE").with(as(customer, Role.CUSTOMER)))
                .andExpect(status().isForbidden());

        String reference = upload(owner, Role.OWNER, "REFUND_EVIDENCE", "../../etc/passwd.pdf", PNG);
        UUID id = UUID.fromString(reference.substring("attachment:".length()));
        assertThat(jdbc.queryForObject("SELECT file_name FROM attachments WHERE id = ?", String.class, id))
                .as("path stripped, extension follows the real type").isEqualTo("passwd.png");
        assertThatThrownBy(() -> jdbc.update("UPDATE attachments SET file_name = 'x' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM attachments WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    private WithdrawalDto requestWithdrawal() {
        tx.executeWithoutResult(s -> wallet.credit(customer, Money.vnd(50_000), WalletEntryType.REFUND_CREDIT,
                "seed:" + UUID.randomUUID(), "Seed"));
        return withdrawals.request(CurrentUser.of(customer, Role.CUSTOMER),
                new WithdrawalRequest(50_000, "Vietcombank", "0123456789", "NGUYEN VAN A"));
    }

    private String upload(UUID user, Role role, String purpose, String name, byte[] bytes) throws Exception {
        String body = mvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", name, "application/octet-stream", bytes))
                        .param("purpose", purpose).with(as(user, role)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        return node.get("reference").asText();
    }

    private static RequestPostProcessor as(UUID user, Role role) {
        return authentication(new UsernamePasswordAuthenticationToken(user.toString(), null,
                List.of(new SimpleGrantedAuthority(role.authority()))));
    }
}
