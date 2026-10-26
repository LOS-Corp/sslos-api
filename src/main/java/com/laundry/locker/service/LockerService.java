package com.laundry.locker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.booking.service.BookingLifecycleService;
import com.laundry.locker.dto.LockerDtos.CompartmentBooking;
import com.laundry.locker.dto.LockerDtos.CompartmentDto;
import com.laundry.locker.dto.LockerDtos.CompartmentState;
import com.laundry.locker.dto.LockerDtos.CustomerLockerAccess;
import com.laundry.locker.dto.LockerDtos.LockerCabinetDto;
import com.laundry.machine.entity.DeviceEvent;
import com.laundry.machine.entity.DeviceEventType;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.repository.DeviceEventRepository;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.mqtt.service.MqttPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Smart locker for the assisted-laundry service (ASSISTED_LOCKER bookings).
 *
 * <pre>
 * CONFIRMED --drop-off PIN, door opened+closed--> CHECKED_IN
 *   --staff COLLECT--> IN_PROGRESS --staff RETURN--> READY_FOR_PICKUP (+ pickup PIN)
 *   --pickup PIN, door opened+closed--> COMPLETED
 * </pre>
 *
 * Device protocol, on the cabinet's {@code laundry/washer/{branchId}/{machineCode}} topics:
 * <ul>
 *   <li>auth ← {@code PIN_ENTERED {requestId, compartment, pin}}: the customer picked a
 *       compartment (A = 1, B = 2) and typed its PIN. A PIN only opens the compartment of
 *       its own booking: a right PIN on the wrong compartment is rejected as WRONG_PIN.</li>
 *   <li>command → {@code OPEN {commandId, requestId, accessId, compartment, purpose}} or
 *       {@code PIN_RESULT {requestId, result: REJECTED, reason}}.</li>
 *   <li>auth ← {@code COMPARTMENT_CLOSED {accessId, compartment, opened}}: the opening ended;
 *       {@code opened=false} means nobody opened the door before the unlock window ran out.</li>
 *   <li>auth ← {@code OPEN_FAILED {accessId, reason}}: the cabinet could not unlock.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LockerService {

    static final Set<String> STAFF_ACTIONS = Set.of("COLLECT", "RETURN", "INSPECT");
    private static final int PIN_DIGITS = 6;
    private static final int MAX_FAILED_PINS = 5;
    private static final long FAILED_PIN_WINDOW_SECONDS = 300;
    /** An unlocked compartment that never reported back (power loss) can be opened again after this. */
    private static final long STALE_OPENING_SECONDS = 180;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final MachineRepository machineRepository;
    private final DeviceEventRepository deviceEventRepository;
    /** Lazy: MqttPublisher → connection → router → WasherMessageHandler → this service. */
    private final ObjectProvider<MqttPublisher> mqttPublisherProvider;
    private final BookingLifecycleService lifecycleService;
    private final SecureRandom random = new SecureRandom();

    /** Live door states reported by each cabinet, by machine id. */
    private final Map<UUID, Map<Integer, CompartmentState>> compartmentStates = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Instant>> failedPins = new ConcurrentHashMap<>();

    /** Minutes after the booking start that the drop-off PIN keeps working. */
    @Value("${app.locker.drop-off-window-minutes:60}")
    private long dropOffWindowMinutes;

    /** The locker compartment a booking holds, with its cabinet (null cabinet = not fitted). */
    record Slot(UUID bookingId, UUID transactionId, String bookingStatus, Instant start,
                Instant allocationEnd, UUID resourceId, String resourceCode, String resourceName,
                UUID machineId, Integer compartmentNo) {
    }

    // ── Customer ──────────────────────────────────────────────────────────

    @Transactional
    public CustomerLockerAccess customerAccess(UUID customerId, UUID bookingId) {
        Slot slot = slotForBooking(bookingId, customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Locker booking not found"));
        Machine cabinet = slot.machineId() == null ? null : machineRepository.findById(slot.machineId()).orElse(null);
        String stage = switch (slot.bookingStatus()) {
            case "HELD", "PAYMENT_REVIEW_REQUIRED" -> "PAYMENT";
            case "CONFIRMED" -> "DROP_OFF";
            case "CHECKED_IN", "IN_PROGRESS" -> "WAITING_STAFF";
            case "READY_FOR_PICKUP" -> "PICKUP";
            case "COMPLETED" -> "DONE";
            default -> "CLOSED";
        };
        if (cabinet == null && !Set.of("DONE", "CLOSED").contains(stage)) stage = "NOT_LINKED";

        Access access = null;
        if (cabinet != null && "DROP_OFF".equals(stage)) {
            access = liveCode(slot, "DROP_OFF").orElseGet(() -> issueCode(slot, "DROP_OFF",
                    slot.start().minusSeconds(15 * 60), slot.start().plusSeconds(dropOffWindowMinutes * 60)));
        } else if (cabinet != null && "PICKUP".equals(stage)) {
            access = liveCode(slot, "PICKUP").orElseGet(() -> issuePickupCode(slot));
        }
        return new CustomerLockerAccess(bookingId, stage,
                cabinet == null ? null : cabinet.getMachineCode(),
                cabinet == null ? null : displayName(cabinet),
                slot.compartmentNo(), slot.resourceName(),
                access == null ? null : access.pin(),
                access == null ? null : access.validFrom(),
                access == null ? null : access.validUntil(),
                cabinet != null && cabinet.isOnline());
    }

    // ── Device messages (auth channel) ──────────────────────────────────

    @Transactional
    public void handlePinEntered(Machine machine, String requestId, Integer compartment, String pin) {
        String code = pin == null ? "" : pin.trim();
        if (lockedOut(machine.getId())) {
            rejectPin(machine, requestId, "LOCKED_OUT");
            return;
        }
        if (compartment == null || compartment < 1) {
            rejectPin(machine, requestId, "SELECT_COMPARTMENT");
            return;
        }
        Instant now = Instant.now();
        List<Access> matches = jdbc.query("""
                SELECT a.id, a.booking_id, a.resource_id, a.purpose, a.pin, a.status, a.valid_from,
                       a.valid_until, a.opened_at, r.compartment_no, b.status AS booking_status, b.transaction_id
                FROM locker_accesses a
                JOIN branch_service_resources r ON r.id = a.resource_id
                JOIN scheduled_bookings b ON b.id = a.booking_id
                WHERE a.machine_id = ? AND r.compartment_no = ? AND a.pin = ?
                  AND a.status IN ('ACTIVE', 'OPENING')
                FOR UPDATE OF a
                """, (rs, row) -> Access.from(rs), machine.getId(), compartment, code);
        if (code.length() != PIN_DIGITS || matches.isEmpty()) {
            recordFailedPin(machine.getId());
            rejectPin(machine, requestId, "WRONG_PIN");
            return;
        }
        Access access = matches.getFirst();
        if (now.isBefore(access.validFrom())) {
            rejectPin(machine, requestId, "TOO_EARLY");
            return;
        }
        if (now.isAfter(access.validUntil())) {
            rejectPin(machine, requestId, "EXPIRED");
            return;
        }
        String needed = "DROP_OFF".equals(access.purpose()) ? "CONFIRMED" : "READY_FOR_PICKUP";
        if (!needed.equals(access.bookingStatus())) {
            rejectPin(machine, requestId, "NOT_READY");
            return;
        }
        if ("OPENING".equals(access.status()) && access.openedAt() != null
                && access.openedAt().isAfter(now.minusSeconds(STALE_OPENING_SECONDS))) {
            rejectPin(machine, requestId, "ALREADY_OPEN");
            return;
        }
        failedPins.remove(machine.getId());
        openCompartment(machine, access.id(), access.compartmentNo(), access.purpose(), requestId, null);
    }

    @Transactional
    public void handleCompartmentClosed(Machine machine, String accessId, Integer compartment, boolean opened) {
        Optional<Access> found = accessForDevice(machine, accessId);
        if (found.isEmpty()) {
            log.warn("[LOCKER] {} closed compartment {} for unknown access {}", machine.getMachineCode(), compartment, accessId);
            return;
        }
        Access access = found.get();
        record(machine, opened ? DeviceEventType.DOOR_CLOSED : DeviceEventType.STATUS_CHANGED, access,
                (opened ? "Compartment " : "Not opened, relocked: compartment ") + access.compartmentNo()
                        + " " + access.purpose());
        if (!opened) {
            // Nobody opened the door: a customer PIN stays usable, a staff opening just ends.
            jdbc.update("UPDATE locker_accesses SET status = ?, open_command_id = NULL WHERE id = ?",
                    access.purpose().startsWith("STAFF_") ? "REVOKED" : "ACTIVE", access.id());
            return;
        }
        jdbc.update("UPDATE locker_accesses SET status = 'USED', used_at = NOW() WHERE id = ?", access.id());
        if (access.bookingId() == null) return;
        switch (access.purpose()) {
            case "DROP_OFF" -> moveBooking(access.bookingId(), "CONFIRMED", "CHECKED_IN");
            case "STAFF_COLLECT" -> moveBooking(access.bookingId(), "CHECKED_IN", "IN_PROGRESS");
            case "STAFF_RETURN" -> {
                if (moveBooking(access.bookingId(), "IN_PROGRESS", "READY_FOR_PICKUP")) {
                    slotForBooking(access.bookingId(), null).ifPresent(this::issuePickupCode);
                }
            }
            case "PICKUP" -> {
                revokeLiveCodes(access.bookingId());
                // Same completion path as a machine booking (status guard + completion email).
                lifecycleService.completeAfterPickup(access.transactionId());
            }
            default -> { }
        }
    }

    @Transactional
    public void handleOpenFailed(Machine machine, String accessId, String reason) {
        accessForDevice(machine, accessId).ifPresent(access -> {
            log.warn("[LOCKER] {} could not open compartment {}: {}", machine.getMachineCode(), access.compartmentNo(), reason);
            jdbc.update("UPDATE locker_accesses SET status = ?, open_command_id = NULL WHERE id = ?",
                    access.purpose().startsWith("STAFF_") ? "REVOKED" : "ACTIVE", access.id());
        });
    }

    /** {@code compartments: [{no, doorClosed, unlocked}]} from a cabinet's status / telemetry. */
    public void recordSnapshot(Machine machine, String payload) {
        try {
            JsonNode list = objectMapper.readTree(payload).path("compartments");
            if (!list.isArray()) return;
            Map<Integer, CompartmentState> states = new LinkedHashMap<>();
            for (JsonNode item : list) {
                int no = item.path("no").asInt(0);
                if (no > 0) states.put(no, new CompartmentState(no,
                        item.path("doorClosed").asBoolean(true), item.path("unlocked").asBoolean(false),
                        item.hasNonNull("key") ? item.get("key").asText() : null,
                        item.hasNonNull("lockGpio") ? item.get("lockGpio").asInt() : null,
                        item.hasNonNull("doorGpio") ? item.get("doorGpio").asInt() : null));
            }
            compartmentStates.put(machine.getId(), states);
        } catch (Exception e) {
            log.debug("[LOCKER] ignoring snapshot from {}: {}", machine.getMachineCode(), e.getMessage());
        }
    }

    // ── Staff ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<LockerCabinetDto> cabinets(UUID branchId) {
        List<LockerCabinetDto> result = new ArrayList<>();
        List<Machine> cabinets = (branchId == null ? machineRepository.findAll() : machineRepository.findByBranchId(branchId))
                .stream().filter(m -> m.getType() == MachineType.LOCKER).toList();
        for (Machine cabinet : cabinets) {
            Map<Integer, CompartmentState> states = compartmentStates.getOrDefault(cabinet.getId(), Map.of());
            List<CompartmentDto> compartments = jdbc.query("""
                    SELECT r.id, r.resource_code, r.display_name, r.compartment_no,
                           b.id AS booking_id, b.status AS booking_status, b.start_time,
                           u.full_name, u.phone
                    FROM branch_service_resources r
                    LEFT JOIN LATERAL (
                        SELECT sb.* FROM booking_resource_allocations a
                        JOIN scheduled_bookings sb ON sb.id = a.booking_id
                        WHERE a.resource_id = r.id
                          AND sb.status IN ('CONFIRMED', 'CHECKED_IN', 'IN_PROGRESS', 'READY_FOR_PICKUP')
                        ORDER BY sb.start_time LIMIT 1
                    ) b ON TRUE
                    LEFT JOIN transactions t ON t.id = b.transaction_id
                    LEFT JOIN users u ON u.id = t.customer_id
                    WHERE r.locker_machine_id = ?
                    ORDER BY r.compartment_no
                    """, (rs, row) -> {
                int no = rs.getInt("compartment_no");
                CompartmentState state = states.get(no);
                UUID bookingId = rs.getObject("booking_id", UUID.class);
                CompartmentBooking booking = bookingId == null ? null : new CompartmentBooking(bookingId,
                        bookingId.toString().substring(0, 8).toUpperCase(Locale.ROOT),
                        rs.getString("full_name"), rs.getString("phone"), rs.getString("booking_status"),
                        rs.getTimestamp("start_time").toInstant());
                return new CompartmentDto(no, rs.getObject("id", UUID.class), rs.getString("resource_code"),
                        rs.getString("display_name"), state == null ? null : state.doorClosed(),
                        state == null ? null : state.unlocked(),
                        state == null ? null : state.key(), state == null ? null : state.lockGpio(),
                        state == null ? null : state.doorGpio(), booking);
            }, cabinet.getId());
            result.add(new LockerCabinetDto(cabinet.getId(), cabinet.getMachineCode(), displayName(cabinet),
                    cabinet.getBranchId(), cabinet.isOnline(), cabinet.isCommissioned(),
                    cabinet.getStatus().name(), compartments));
        }
        return result;
    }

    /** Creates the missing compartments {@code <code>-1..count} as bookable LOCKER resources of the cabinet. */
    @Transactional
    public LockerCabinetDto syncCompartments(UUID machineId, int count) {
        Machine cabinet = cabinet(machineId);
        for (int no = 1; no <= count; no++) {
            Integer exists = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM branch_service_resources WHERE locker_machine_id = ? AND compartment_no = ?
                    """, Integer.class, machineId, no);
            if (exists != null && exists > 0) continue;
            String code = cabinet.getMachineCode() + "-" + no;
            jdbc.update("""
                    INSERT INTO branch_service_resources
                      (id, branch_id, resource_type, resource_code, display_name, enabled, locker_machine_id, compartment_no)
                    VALUES (?, ?, 'LOCKER', ?, ?, TRUE, ?, ?)
                    ON CONFLICT (branch_id, resource_code) DO UPDATE
                      SET locker_machine_id = EXCLUDED.locker_machine_id, compartment_no = EXCLUDED.compartment_no
                    """, UUID.randomUUID(), cabinet.getBranchId(), code,
                    displayName(cabinet) + " · Ô " + no, machineId, no);
        }
        return cabinets(cabinet.getBranchId()).stream()
                .filter(c -> c.machineId().equals(machineId)).findFirst().orElseThrow();
    }

    @Transactional
    public void staffOpen(UUID machineId, int compartmentNo, String action, UUID staffId) {
        String act = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if (!STAFF_ACTIONS.contains(act)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action must be COLLECT, RETURN or INSPECT");
        }
        Machine cabinet = cabinet(machineId);
        if (!cabinet.isOnline()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tủ đang mất kết nối");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.id AS resource_id,
                       (SELECT sb.id FROM booking_resource_allocations a
                          JOIN scheduled_bookings sb ON sb.id = a.booking_id
                         WHERE a.resource_id = r.id
                           AND sb.status IN ('CONFIRMED', 'CHECKED_IN', 'IN_PROGRESS', 'READY_FOR_PICKUP')
                         ORDER BY sb.start_time LIMIT 1) AS booking_id
                FROM branch_service_resources r
                WHERE r.locker_machine_id = ? AND r.compartment_no = ?
                """, machineId, compartmentNo);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ô tủ chưa được tạo");
        }
        UUID resourceId = (UUID) rows.getFirst().get("resource_id");
        UUID bookingId = (UUID) rows.getFirst().get("booking_id");
        if (!"INSPECT".equals(act)) {
            String needed = "COLLECT".equals(act) ? "CHECKED_IN" : "IN_PROGRESS";
            String status = bookingId == null ? null
                    : jdbc.queryForObject("SELECT status FROM scheduled_bookings WHERE id = ?", String.class, bookingId);
            if (!needed.equals(status)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "COLLECT".equals(act)
                        ? "Ô này chưa có đồ khách gửi" : "Đơn này chưa được lấy đi xử lý");
            }
        }
        UUID accessId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO locker_accesses (id, booking_id, resource_id, machine_id, purpose, status,
                                             valid_from, valid_until, opened_by)
                VALUES (?, ?, ?, ?, ?, 'OPENING', ?, ?, ?)
                """, accessId, "INSPECT".equals(act) ? null : bookingId, resourceId, machineId, "STAFF_" + act,
                utc(now), utc(now.plusSeconds(STALE_OPENING_SECONDS)), staffId);
        openCompartment(cabinet, accessId, compartmentNo, "STAFF_" + act, null, staffId);
    }

    // ── Internals ─────────────────────────────────────────────────────────

    record Access(UUID id, UUID bookingId, UUID resourceId, String purpose, String pin, String status,
                  Instant validFrom, Instant validUntil, Instant openedAt, int compartmentNo,
                  String bookingStatus, UUID transactionId) {
        static Access from(java.sql.ResultSet rs) throws java.sql.SQLException {
            Timestamp opened = rs.getTimestamp("opened_at");
            return new Access(rs.getObject("id", UUID.class), rs.getObject("booking_id", UUID.class),
                    rs.getObject("resource_id", UUID.class), rs.getString("purpose"), rs.getString("pin"),
                    rs.getString("status"), rs.getTimestamp("valid_from").toInstant(),
                    rs.getTimestamp("valid_until").toInstant(), opened == null ? null : opened.toInstant(),
                    rs.getInt("compartment_no"), rs.getString("booking_status"),
                    rs.getObject("transaction_id", UUID.class));
        }
    }

    private void openCompartment(Machine machine, UUID accessId, int compartmentNo, String purpose,
                                 String requestId, UUID staffId) {
        String commandId = "LOCK-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                UPDATE locker_accesses SET status = 'OPENING', open_command_id = ?, opened_at = NOW(),
                       opened_by = COALESCE(?, opened_by)
                WHERE id = ?
                """, commandId, staffId, accessId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "OPEN");
        payload.put("commandId", commandId);
        if (requestId != null) payload.put("requestId", requestId);
        payload.put("accessId", accessId.toString());
        payload.put("compartment", compartmentNo);
        payload.put("purpose", purpose);
        log.info("[LOCKER] {} OPEN compartment {} ({})", machine.getMachineCode(), compartmentNo, purpose);
        publishAfterCommit(machine, payload);
    }

    private void rejectPin(Machine machine, String requestId, String reason) {
        log.info("[LOCKER] {} PIN rejected: {}", machine.getMachineCode(), reason);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "PIN_RESULT");
        payload.put("requestId", requestId == null ? "" : requestId);
        payload.put("result", "REJECTED");
        payload.put("reason", reason);
        publishAfterCommit(machine, payload);
    }

    private void publishAfterCommit(Machine machine, Map<String, Object> payload) {
        Runnable send = () -> {
            try {
                MqttPublisher mqttPublisher = mqttPublisherProvider.getObject();
                mqttPublisher.publish(mqttPublisher.buildWasherCommandTopic(machine.getBranchId(), machine.getMachineCode()),
                        objectMapper.writeValueAsString(payload));
            } catch (Exception e) {
                log.error("[LOCKER] cannot send {} to {}: {}", payload.get("type"), machine.getMachineCode(), e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private Optional<Access> accessForDevice(Machine machine, String accessId) {
        UUID id;
        try {
            id = UUID.fromString(accessId == null ? "" : accessId.trim());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT a.id, a.booking_id, a.resource_id, a.purpose, a.pin, a.status, a.valid_from,
                       a.valid_until, a.opened_at, r.compartment_no, b.status AS booking_status, b.transaction_id
                FROM locker_accesses a
                JOIN branch_service_resources r ON r.id = a.resource_id
                LEFT JOIN scheduled_bookings b ON b.id = a.booking_id
                WHERE a.id = ? AND a.machine_id = ? AND a.status = 'OPENING'
                FOR UPDATE OF a
                """, (rs, row) -> Access.from(rs), id, machine.getId()).stream().findFirst();
    }

    private Optional<Slot> slotForBooking(UUID bookingId, UUID customerId) {
        return jdbc.query("""
                SELECT b.id, b.transaction_id, b.status, b.start_time, a.occupied_until,
                       r.id AS resource_id, r.resource_code, r.display_name, r.locker_machine_id, r.compartment_no
                FROM scheduled_bookings b
                JOIN transactions t ON t.id = b.transaction_id
                JOIN booking_resource_allocations a ON a.booking_id = b.id AND a.resource_type = 'LOCKER'
                JOIN branch_service_resources r ON r.id = a.resource_id
                WHERE b.id = ? AND (?::uuid IS NULL OR t.customer_id = ?)
                ORDER BY a.occupied_until DESC LIMIT 1
                """, (rs, row) -> new Slot(rs.getObject("id", UUID.class), rs.getObject("transaction_id", UUID.class),
                rs.getString("status"), rs.getTimestamp("start_time").toInstant(),
                rs.getTimestamp("occupied_until").toInstant(), rs.getObject("resource_id", UUID.class),
                rs.getString("resource_code"), rs.getString("display_name"),
                rs.getObject("locker_machine_id", UUID.class),
                (Integer) rs.getObject("compartment_no")), bookingId, customerId, customerId).stream().findFirst();
    }

    private Optional<Access> liveCode(Slot slot, String purpose) {
        return jdbc.query("""
                SELECT a.id, a.booking_id, a.resource_id, a.purpose, a.pin, a.status, a.valid_from,
                       a.valid_until, a.opened_at, r.compartment_no, b.status AS booking_status, b.transaction_id
                FROM locker_accesses a
                JOIN branch_service_resources r ON r.id = a.resource_id
                JOIN scheduled_bookings b ON b.id = a.booking_id
                WHERE a.booking_id = ? AND a.purpose = ? AND a.status IN ('ACTIVE', 'OPENING')
                ORDER BY a.created_at DESC LIMIT 1
                """, (rs, row) -> Access.from(rs), slot.bookingId(), purpose).stream().findFirst();
    }

    private Access issuePickupCode(Slot slot) {
        return issueCode(slot, "PICKUP", Instant.now(), slot.allocationEnd());
    }

    private Access issueCode(Slot slot, String purpose, Instant from, Instant until) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String pin = String.format("%06d", random.nextInt(1_000_000));
            Integer taken = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM locker_accesses
                    WHERE machine_id = ? AND pin = ? AND status IN ('ACTIVE', 'OPENING')
                    """, Integer.class, slot.machineId(), pin);
            if (taken != null && taken > 0) continue;
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO locker_accesses (id, booking_id, resource_id, machine_id, purpose, pin, status,
                                                 valid_from, valid_until)
                    VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                    """, id, slot.bookingId(), slot.resourceId(), slot.machineId(), purpose, pin, utc(from), utc(until));
            log.info("[LOCKER] {} PIN issued for booking {}", purpose, slot.bookingId());
            return new Access(id, slot.bookingId(), slot.resourceId(), purpose, pin, "ACTIVE", from, until, null,
                    slot.compartmentNo() == null ? 0 : slot.compartmentNo(), slot.bookingStatus(), slot.transactionId());
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không tạo được mã PIN, thử lại");
    }

    private boolean moveBooking(UUID bookingId, String from, String to) {
        int updated = jdbc.update("""
                UPDATE scheduled_bookings SET status = ?, updated_at = NOW() WHERE id = ? AND status = ?
                """, to, bookingId, from);
        if (updated > 0) log.info("[LOCKER] booking {} {} -> {}", bookingId, from, to);
        return updated > 0;
    }

    private void revokeLiveCodes(UUID bookingId) {
        jdbc.update("""
                UPDATE locker_accesses SET status = 'REVOKED'
                WHERE booking_id = ? AND status IN ('ACTIVE', 'OPENING')
                """, bookingId);
    }

    private boolean lockedOut(UUID machineId) {
        Deque<Instant> failures = failedPins.get(machineId);
        if (failures == null) return false;
        synchronized (failures) {
            Instant cutoff = Instant.now().minusSeconds(FAILED_PIN_WINDOW_SECONDS);
            while (!failures.isEmpty() && failures.peekFirst().isBefore(cutoff)) failures.pollFirst();
            return failures.size() >= MAX_FAILED_PINS;
        }
    }

    private void recordFailedPin(UUID machineId) {
        Deque<Instant> failures = failedPins.computeIfAbsent(machineId, id -> new ArrayDeque<>());
        synchronized (failures) {
            failures.addLast(Instant.now());
        }
    }

    private void record(Machine machine, DeviceEventType type, Access access, String detail) {
        deviceEventRepository.save(DeviceEvent.builder()
                .machineId(machine.getId())
                .machineCode(machine.getMachineCode())
                .eventType(type)
                .detail(detail)
                .bookingId(access.bookingId())
                .build());
    }

    private Machine cabinet(UUID machineId) {
        Machine machine = machineRepository.findById(machineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Locker not found"));
        if (machine.getType() != MachineType.LOCKER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thiết bị này không phải tủ locker");
        }
        return machine;
    }

    private static String displayName(Machine machine) {
        return machine.getDisplayName() == null || machine.getDisplayName().isBlank()
                ? machine.getMachineCode() : machine.getDisplayName();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
