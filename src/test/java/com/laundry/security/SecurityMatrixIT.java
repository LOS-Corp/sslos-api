package com.laundry.security;

import com.laundry.audit.repository.AuditLogRepository;
import com.laundry.branch.entity.Branch;
import com.laundry.branch.repository.BranchRepository;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineDeploymentStatus;
import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.shared.security.Role;
import com.laundry.support.IntegrationTest;
import com.laundry.user.entity.BranchAssignment;
import com.laundry.user.entity.User;
import com.laundry.user.repository.BranchAssignmentRepository;
import com.laundry.user.repository.RoleRepository;
import com.laundry.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The permission matrix of task/09 exercised through the real security filter chain:
 * URL rules, method security, ownership and Staff branch scope.
 */
@IntegrationTest
@AutoConfigureMockMvc
class SecurityMatrixIT {

    @Autowired private MockMvc mvc;
    @Autowired private BranchRepository branches;
    @Autowired private MachineRepository machines;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private BranchAssignmentRepository assignments;
    @Autowired private AuditLogRepository auditLogs;

    private UUID branchA;
    private String machineA;
    private UUID staffOfA;
    private UUID staffOfB;

    @BeforeEach
    void setUp() {
        branchA = branch();
        UUID branchB = branch();
        machineA = machine(branchA);
        staffOfA = staff(branchA);
        staffOfB = staff(branchB);
    }

    @Test
    @DisplayName("SC-27 / NFR-13: removed purchase and callback endpoints are unreachable")
    void legacyEndpointsAreGone() throws Exception {
        mvc.perform(post("/api/kiosk/purchases").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/mobile/purchases").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/payments/callback/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUCCESS\"}"))
                .andExpect(status().isUnauthorized());
        // Staff may reach /api/staff/** (lockers); the counter endpoint itself no longer exists.
        mvc.perform(post("/api/staff/purchases").with(as(Role.STAFF))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("Deny by default: an unlisted endpoint is refused even for Admin")
    void unknownEndpointIsDenied() throws Exception {
        mvc.perform(get("/api/not-in-the-matrix").with(as(Role.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.messageCode").value("MSG22"));
    }

    @Test
    @DisplayName("Public reads stay public; operational history needs an operator")
    void publicAndOperationalReads() throws Exception {
        mvc.perform(get("/api/branches")).andExpect(status().isOk());
        mvc.perform(get("/api/machines/" + machineA + "/events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/machines/" + machineA + "/commands").with(as(Role.CUSTOMER)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/machines/" + machineA + "/commands").with(as(staffOfB, Role.STAFF)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_OUT_OF_SCOPE"));
        mvc.perform(get("/api/machines/" + machineA + "/commands").with(as(staffOfA, Role.STAFF)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("BR-01: Staff of another branch cannot list or open the lockers of this branch")
    void staffLockerScope() throws Exception {
        mvc.perform(get("/api/staff/lockers").param("branchId", branchA.toString()).with(as(staffOfB, Role.STAFF)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_OUT_OF_SCOPE"));
        mvc.perform(get("/api/staff/lockers").param("branchId", branchA.toString()).with(as(staffOfA, Role.STAFF)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/staff/lockers").with(as(Role.ADMIN))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SC-20: a customer cannot STOP a machine that is not running their cycle")
    void customerCannotStopSomeoneElsesMachine() throws Exception {
        mvc.perform(command(machineA, "STOP", null).with(as(Role.CUSTOMER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("SC-45 / BR-25: overrides need a reason, stay in scope and are audited")
    void operatorOverrides() throws Exception {
        mvc.perform(command(machineA, "STOP", null).with(as(staffOfA, Role.STAFF)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"))
                .andExpect(jsonPath("$.messageCode").value("MSG24"));
        mvc.perform(command(machineA, "STOP", "Door jammed").with(as(staffOfB, Role.STAFF)))
                .andExpect(status().isForbidden());
        mvc.perform(command(machineA, "SELF_DESTRUCT", "x").with(as(Role.OWNER)))
                .andExpect(status().isBadRequest());

        mvc.perform(command(machineA, "STOP", "Customer reported smoke").with(as(staffOfA, Role.STAFF)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.command").value("STOP"));
        assertThat(auditLogs.findByEntityTypeAndEntityIdOrderByOccurredAtAsc("MACHINE", machineA))
                .anySatisfy(entry -> {
                    assertThat(entry.getAction()).isEqualTo("REMOTE_COMMAND");
                    assertThat(entry.getReason()).isEqualTo("Customer reported smoke");
                    assertThat(entry.getActorId()).isEqualTo(staffOfA);
                });
    }

    @Test
    @DisplayName("SC-16 / SC-22: Staff cannot approve refunds; Admin cannot touch prices, branches or refunds")
    void roleBoundaries() throws Exception {
        String anyRefund = "/api/refunds/" + UUID.randomUUID();
        mvc.perform(post(anyRefund + "/approve").with(as(Role.STAFF))).andExpect(status().isForbidden());
        mvc.perform(post(anyRefund + "/approve").with(as(Role.ADMIN))).andExpect(status().isForbidden());

        mvc.perform(get("/api/admin/booking-config/branches").with(as(Role.ADMIN))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/booking-config/branches").with(as(Role.OWNER))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/branches").with(as(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/iot-devices").with(as(Role.OWNER))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").with(as(Role.STAFF))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Error bodies carry code and traceId; the trace id is echoed in a header")
    void errorShape() throws Exception {
        mvc.perform(get("/api/machines/" + machineA + "/events").header("X-Trace-Id", "trace-abc-12345"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.traceId").value("trace-abc-12345"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static MockHttpServletRequestBuilder command(String machineCode, String command, String reason) {
        String body = reason == null
                ? "{\"command\":\"" + command + "\"}"
                : "{\"command\":\"" + command + "\",\"reason\":\"" + reason + "\"}";
        return post("/api/machines/" + machineCode + "/commands").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static RequestPostProcessor as(Role role) {
        return as(UUID.randomUUID(), role);
    }

    private static RequestPostProcessor as(UUID userId, Role role) {
        return authentication(new UsernamePasswordAuthenticationToken(userId.toString(), null,
                List.of(new SimpleGrantedAuthority(role.authority()))));
    }

    private UUID branch() {
        return branches.save(Branch.builder().name("Matrix " + UUID.randomUUID()).build()).getId();
    }

    private String machine(UUID branchId) {
        String code = "M" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        machines.save(Machine.builder()
                .machineCode(code)
                .branchId(branchId)
                .type(MachineType.WASHER)
                .status(MachineStatus.AVAILABLE)
                .deploymentStatus(MachineDeploymentStatus.ACTIVE)
                .online(true)
                .build());
        return code;
    }

    private UUID staff(UUID branchId) {
        User user = new User("Staff", UUID.randomUUID() + "@test.vn",
                "+849" + String.format("%08d", (long) (Math.random() * 100_000_000)), "x");
        user.addRole(roles.findByName("STAFF").orElseThrow());
        UUID id = users.saveAndFlush(user).getId();
        assignments.save(new BranchAssignment(id, branchId, null, OffsetDateTime.now()));
        return id;
    }
}
