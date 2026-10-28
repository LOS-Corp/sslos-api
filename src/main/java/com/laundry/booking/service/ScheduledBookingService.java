package com.laundry.booking.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.booking.dto.MachineProgramDtos.MachineProgramDto;
import com.laundry.branch.entity.Branch;
import com.laundry.branch.entity.BranchStatus;
import com.laundry.branch.repository.BranchRepository;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.transaction.entity.ServiceType;
import com.laundry.transaction.entity.Transaction;
import com.laundry.transaction.entity.TransactionStatus;
import com.laundry.transaction.repository.TransactionRepository;
import com.laundry.config.BookingProperties;
import com.laundry.payment.service.WalletPaymentService;
import com.laundry.shared.money.Money;
import com.laundry.wallet.entity.WalletEntryType;
import com.laundry.wallet.service.WalletService;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import com.laundry.machine.entity.Machine;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static com.laundry.booking.dto.BookingDtos.*;
import static com.laundry.booking.dto.BookingConfigDtos.*;

import com.laundry.booking.domain.SlotPlanner.*;
import static com.laundry.booking.domain.SlotPlanner.*;

/**
 * Holds and their payment outcome (UC-02, BR-04): an atomic, idempotent 5-minute hold; payment
 * inside the hold confirms, after it goes to review; cancellation and expiry release resources.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduledBookingService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final BranchRepository branchRepository;
    private final MachineRepository machineRepository;
    private final TransactionRepository transactionRepository;
    private final MachineProgramService programService;
    private final BookingResourceQueries queries;
    private final WalletService wallet;
    private final WalletPaymentService walletPayments;
    private final BookingProperties props;


    @Transactional
    public BookingDto createHold(UUID customerId, CreateHoldRequest request) {
        if (!isMinuteAligned(request.startTime())) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Start time must be aligned to a whole minute");
        }
        if (request.startTime().isAfter(Instant.now().plus(props.maxAdvance()))) {
            throw BusinessException.of(ErrorCode.BEYOND_ADVANCE_HORIZON, "You can book at most 48 hours ahead");
        }
        if (request.idempotencyKey().length() > 120) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "idempotencyKey is too long");
        }
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))",
                Object.class, "booking-hold:" + request.idempotencyKey());

        UUID existingBooking = jdbc.query("""
                SELECT id FROM scheduled_bookings WHERE idempotency_key = ?
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, request.idempotencyKey());
        if (existingBooking != null) {
            return getBooking(customerId, existingBooking);
        }

        queries.lockBranchConfiguration(request.branchId());
        ServiceConfig offered = queries.offering(request.branchId(), request.serviceCode());
        if (offered == null) {
            throw BusinessException.of(ErrorCode.NOT_FOUND, "This service is not configured for the selected branch");
        }
        List<MachineProgramDto> programs = programService.resolve(
                offered.requirements(), request.washProgram(), request.dryProgram());
        ServiceConfig config = withPrograms(offered, programs);
        Branch branch = branchRepository.findById(request.branchId())
                .filter(candidate -> candidate.getStatus() == BranchStatus.ACTIVE)
                .orElseThrow(() -> BusinessException.of(ErrorCode.NOT_FOUND, "Branch not found"));
        SlotPlan plan = buildPlan(config, request.startTime());
        if (request.resourceIds().size() != config.requirements().size()
                || request.resourceIds().stream().distinct().count() != request.resourceIds().size()) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Select exactly one unique resource for each service requirement");
        }
        List<Resource> resources = new ArrayList<>();
        for (int i = 0; i < config.requirements().size(); i++) {
            String requiredType = config.requirements().get(i);
            UUID selectedId = request.resourceIds().get(i);
            Resource selectedResource = queries.resourcesFor(config.branchId(), requiredType).stream()
                    .filter(resource -> resource.id().equals(selectedId))
                    .findFirst().orElse(null);
            if (selectedResource == null) {
                throw BusinessException.of(ErrorCode.CONFLICT, "A selected resource is not compatible with this service");
            }
            resources.add(selectedResource);
        }
        requireCapacity(resources, request.declaredLoadKg());
        if (!queries.isWithinOpeningHours(config, request.startTime(), plan)) {
            throw BusinessException.of(ErrorCode.OUTSIDE_OPENING_HOURS, "The requested time is outside branch opening hours");
        }
        lockResources(resources);
        expireStaleHolds(resources);
        validateSelectedResources(request.branchId(), resources);
        for (int i = 0; i < resources.size(); i++) {
            if (queries.resourceBusy(resources.get(i), plan.allocationPlans().get(i), config.bufferMinutes())) {
                throw BusinessException.of(ErrorCode.SLOT_TAKEN, "The selected time was just booked. Please choose another slot.");
            }
        }

        Instant now = Instant.now();
        boolean advance = plan.start().isAfter(now.plus(props.useNowWindow()));
        if (advance) {
            requireAdvanceQuota(config, plan);
        }
        Instant holdExpiresAt = now.plus(props.holdDuration());
        Transaction transaction = Transaction.builder()
                .customerId(customerId)
                .serviceType("ASSISTED_LOCKER".equals(config.mode())
                        ? ServiceType.LAUNDRY_SERVICE : ServiceType.SELF_SERVICE)
                .totalAmount(config.price())
                .status(TransactionStatus.PENDING_PAYMENT)
                .expiresAt(LocalDateTime.ofInstant(holdExpiresAt, ZoneOffset.UTC))
                .build();
        transaction = transactionRepository.saveAndFlush(transaction);

        UUID bookingId = UUID.randomUUID();
        String timelineJson;
        try {
            timelineJson = objectMapper.writeValueAsString(plan.timeline());
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize booking timeline", e);
        }
        jdbc.update("""
                INSERT INTO scheduled_bookings
                  (id, transaction_id, branch_id, service_code, status, start_time,
                   expected_end_time, hold_expires_at, timeline, idempotency_key, programs,
                   advance_booking, declared_load_kg)
                VALUES (?, ?, ?, ?, 'HELD', ?, ?, ?, CAST(? AS JSONB), ?, CAST(? AS JSONB), ?, ?)
                """, bookingId, transaction.getId(), branch.getId(), config.code(),
                OffsetDateTime.ofInstant(plan.start(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(plan.expectedEnd(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(holdExpiresAt, ZoneOffset.UTC),
                timelineJson, request.idempotencyKey(), programService.toJson(programs),
                advance, request.declaredLoadKg());

        for (int i = 0; i < resources.size(); i++) {
            Resource resource = resources.get(i);
            AllocationPlan allocation = plan.allocationPlans().get(i);
            // A washer/dryer allocation also tracks its machine's cycle (V51): PENDING until START.
            boolean machine = "WASHER".equals(resource.type()) || "DRYER".equals(resource.type());
            jdbc.update("""
                    INSERT INTO booking_resource_allocations
                      (booking_id, resource_id, resource_type, phase, occupied_from, occupied_until, cycle_state)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, bookingId, resource.id(), resource.type(), allocation.phase(),
                    OffsetDateTime.ofInstant(allocation.from(), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(allocation.until(), ZoneOffset.UTC),
                    machine ? "PENDING" : null);
        }
        return getBooking(customerId, bookingId);
    }

    @Transactional(readOnly = true)
    public BookingDto getBooking(UUID customerId, UUID bookingId) {
        List<BookingDto> bookings = jdbc.query("""
                SELECT sb.id booking_id, t.id transaction_id, sd.code service_code,
                       sd.name service_name, sd.service_mode, sb.status booking_status,
                       t.status transaction_status, sb.branch_id, b.name branch_name,
                       b.address branch_address, b.phone branch_phone, sb.start_time,
                       sb.expected_end_time, sb.hold_expires_at, t.total_amount,
                       sb.timeline::text timeline, sb.programs::text programs,
                       p.payment_id, p.status payment_status, p.checkout_url payment_url, p.qr_code,
                       allocated.booking_resources
                FROM scheduled_bookings sb
                JOIN transactions t ON t.id = sb.transaction_id
                JOIN booking_service_definitions sd ON sd.code = sb.service_code
                JOIN branches b ON b.id = sb.branch_id
                LEFT JOIN LATERAL (
                    SELECT id payment_id, status, checkout_url, qr_code
                    FROM payments WHERE transaction_id = t.id
                    ORDER BY created_at DESC LIMIT 1
                ) p ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COALESCE(jsonb_agg(jsonb_build_object(
                        'resourceId', a.resource_id,
                        'resourceType', a.resource_type,
                        'resourceCode', COALESCE(m.machine_code, r.resource_code),
                        'displayName', COALESCE(NULLIF(m.display_name, ''), r.display_name, m.machine_code),
                        'location', m.location_description,
                        'status', COALESCE(m.status, CASE WHEN r.enabled THEN 'ASSIGNED' ELSE 'DISABLED' END)
                    ) ORDER BY a.resource_type), '[]'::jsonb)::text booking_resources
                    FROM booking_resource_allocations a
                    LEFT JOIN machines m ON m.id = a.resource_id
                    LEFT JOIN branch_service_resources r ON r.id = a.resource_id
                    WHERE a.booking_id = sb.id
                ) allocated ON TRUE
                WHERE sb.id = ? AND t.customer_id = ?
                """, (rs, row) -> mapBooking(rs), bookingId, customerId);
        if (bookings.isEmpty()) {
            throw BusinessException.of(ErrorCode.NOT_FOUND, "Booking not found");
        }
        return bookings.getFirst();
    }

    @Transactional(readOnly = true)
    public List<BookingDto> getCustomerBookings(UUID customerId) {
        return jdbc.query("""
                SELECT sb.id booking_id, t.id transaction_id, sd.code service_code,
                       sd.name service_name, sd.service_mode, sb.status booking_status,
                       t.status transaction_status, sb.branch_id, b.name branch_name,
                       b.address branch_address, b.phone branch_phone, sb.start_time,
                       sb.expected_end_time, sb.hold_expires_at, t.total_amount,
                       sb.timeline::text timeline, sb.programs::text programs,
                       p.payment_id, p.status payment_status, p.checkout_url payment_url, p.qr_code,
                       allocated.booking_resources
                FROM scheduled_bookings sb
                JOIN transactions t ON t.id = sb.transaction_id
                JOIN booking_service_definitions sd ON sd.code = sb.service_code
                JOIN branches b ON b.id = sb.branch_id
                LEFT JOIN LATERAL (
                    SELECT id payment_id, status, checkout_url, qr_code
                    FROM payments WHERE transaction_id = t.id
                    ORDER BY created_at DESC LIMIT 1
                ) p ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COALESCE(jsonb_agg(jsonb_build_object(
                        'resourceId', a.resource_id,
                        'resourceType', a.resource_type,
                        'resourceCode', COALESCE(m.machine_code, r.resource_code),
                        'displayName', COALESCE(NULLIF(m.display_name, ''), r.display_name, m.machine_code),
                        'location', m.location_description,
                        'status', COALESCE(m.status, CASE WHEN r.enabled THEN 'ASSIGNED' ELSE 'DISABLED' END)
                    ) ORDER BY a.resource_type), '[]'::jsonb)::text booking_resources
                    FROM booking_resource_allocations a
                    LEFT JOIN machines m ON m.id = a.resource_id
                    LEFT JOIN branch_service_resources r ON r.id = a.resource_id
                    WHERE a.booking_id = sb.id
                ) allocated ON TRUE
                WHERE t.customer_id = ?
                ORDER BY sb.created_at DESC
                """, (rs, row) -> mapBooking(rs), customerId);
    }

    @Transactional
    public UUID prepareCheckout(UUID customerId, UUID bookingId) {
        List<UUID> transactionIds = jdbc.query("""
                SELECT sb.transaction_id
                FROM scheduled_bookings sb
                JOIN transactions t ON t.id = sb.transaction_id
                WHERE sb.id = ? AND t.customer_id = ?
                  AND sb.status = 'HELD'
                  AND sb.hold_expires_at > NOW()
                  AND sb.start_time > NOW()
                FOR UPDATE OF sb, t
                """, (rs, row) -> rs.getObject(1, UUID.class), bookingId, customerId);
        if (transactionIds.isEmpty()) {
            throw BusinessException.of(ErrorCode.HOLD_EXPIRED, "Booking hold has expired");
        }
        return transactionIds.getFirst();
    }

    @Transactional
    public void cancelBooking(UUID customerId, UUID bookingId) {
        BookingDto booking = getBooking(customerId, bookingId);
        if (!"HELD".equals(booking.status())) {
            throw BusinessException.of(ErrorCode.CONFLICT, "This booking cannot be cancelled");
        }
        jdbc.update("""
                UPDATE scheduled_bookings SET status = 'CANCELLED', updated_at = NOW()
                WHERE id = ?
                """, bookingId);
        transactionRepository.findById(booking.transactionId()).ifPresent(transaction -> {
            transaction.setStatus(TransactionStatus.CANCELLED);
            transactionRepository.save(transaction);
        });
        walletPayments.reverse(List.of(booking.transactionId()), "Hoàn phần ví: khách huỷ giữ chỗ");
    }

    public boolean hasScheduledBooking(UUID transactionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_bookings WHERE transaction_id = ?",
                Integer.class, transactionId);
        return count != null && count > 0;
    }

    @Transactional
    public void confirmPayment(UUID transactionId) {
        Transaction transaction = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.NOT_FOUND, "Transaction not found"));
        List<UUID> bookingIds = jdbc.query("""
                SELECT id FROM scheduled_bookings WHERE transaction_id = ? FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class), transactionId);
        if (bookingIds.isEmpty()) {
            throw BusinessException.of(ErrorCode.NOT_FOUND, "Scheduled booking not found");
        }
        UUID bookingId = bookingIds.getFirst();
        String bookingStatus = jdbc.queryForObject(
                "SELECT status FROM scheduled_bookings WHERE id = ?",
                String.class, bookingId);
        if ("CONFIRMED".equals(bookingStatus)) {
            return;
        }
        if ("CANCELLED".equals(bookingStatus)) {
            transaction.setStatus(TransactionStatus.PAID);
            transaction.setPaidAt(LocalDateTime.now(ZoneOffset.UTC));
            transactionRepository.save(transaction);
            jdbc.update("""
                    UPDATE scheduled_bookings SET status = 'PAYMENT_REVIEW_REQUIRED',
                        reconciliation_reason = 'Payment succeeded after cancellation; refund or manual reconciliation is required',
                        updated_at = NOW()
                    WHERE id = ?
                    """, bookingId);
            log.error("Cancelled booking {} was paid after cancellation and requires reconciliation", bookingId);
            return;
        }
        if (!"HELD".equals(bookingStatus) && !"EXPIRED".equals(bookingStatus)) {
            throw BusinessException.of(ErrorCode.CONFLICT, "Booking is not eligible for payment confirmation");
        }

        boolean holdExpired = !"PENDING_PAYMENT".equals(transaction.getStatus().name())
                || jdbc.queryForObject("""
                    SELECT hold_expires_at <= NOW()
                    FROM scheduled_bookings WHERE id = ?
                    """, Boolean.class, bookingId);
        boolean startPassed = jdbc.queryForObject("""
                SELECT start_time <= NOW() FROM scheduled_bookings WHERE id = ?
                """, Boolean.class, bookingId);
        // BR-04 / SC-17: payment after the hold never re-takes the slot, even if it is still free.
        if (startPassed || holdExpired) {
            transaction.setStatus(TransactionStatus.PAID);
            transaction.setPaidAt(LocalDateTime.now(ZoneOffset.UTC));
            transactionRepository.save(transaction);
            jdbc.update("""
                    UPDATE scheduled_bookings SET status = 'PAYMENT_REVIEW_REQUIRED',
                        reconciliation_reason = ?,
                        updated_at = NOW()
                    WHERE id = ?
                    """, startPassed
                    ? "Payment succeeded after the booked start time; refund or manual reconciliation is required"
                    : "Payment succeeded after the hold expired; the slot is not re-taken automatically (BR-04)",
                    bookingId);
            log.warn("Paid booking {} requires reconciliation: startPassed={}, holdExpired={}",
                    bookingId, startPassed, holdExpired);
            // MSG11: the money is not lost. The wallet part (if any) is reversed once; what was paid
            // outside the wallet is credited — so no order of events can pay the customer twice.
            walletPayments.reverse(List.of(transactionId), "Hoàn phần ví: thanh toán đến sau khi hết giữ chỗ");
            Money paidOutsideWallet = Money.of(transaction.getTotalAmount())
                    .minus(walletPayments.walletPortion(transactionId));
            if (transaction.getCustomerId() != null && paidOutsideWallet.isPositive()) {
                wallet.credit(transaction.getCustomerId(), paidOutsideWallet,
                        WalletEntryType.LATE_PAYMENT_CREDIT, "late-payment:" + transactionId,
                        "Thanh toán sau khi hết thời gian giữ chỗ");
            }
            return;
        }

        transaction.setStatus(TransactionStatus.PAID);
        transaction.setPaidAt(LocalDateTime.now(ZoneOffset.UTC));
        transactionRepository.save(transaction);
        jdbc.update("""
                UPDATE scheduled_bookings SET status = 'CONFIRMED', updated_at = NOW()
                WHERE id = ?
                """, bookingId);
    }

    @Transactional
    public void cancelForPaymentFailure(UUID transactionId) {
        Transaction transaction = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.NOT_FOUND, "Transaction not found"));
        jdbc.update("""
                UPDATE scheduled_bookings SET status = 'CANCELLED', updated_at = NOW()
                WHERE transaction_id = ? AND status IN ('HELD', 'EXPIRED')
                """, transactionId);
        if (transaction.getStatus() == TransactionStatus.PENDING_PAYMENT
                || transaction.getStatus() == TransactionStatus.EXPIRED) {
            transaction.setStatus(TransactionStatus.CANCELLED);
            transactionRepository.save(transaction);
        }
    }

    /**
     * BR-26 / SC-42: never book a machine smaller than the declared load. Machines without a declared
     * capacity are not blocked (the prototype has no load cell, FR-CAT-03).
     */
    private void requireCapacity(List<Resource> resources, BigDecimal declaredLoadKg) {
        if (declaredLoadKg == null) {
            return;
        }
        for (Resource resource : resources) {
            if (!"WASHER".equals(resource.type()) && !"DRYER".equals(resource.type())) {
                continue;
            }
            BigDecimal capacity = machineRepository.findById(resource.id()).map(Machine::getCapacityKg).orElse(null);
            if (capacity != null && capacity.compareTo(declaredLoadKg) < 0) {
                throw BusinessException.of(ErrorCode.MACHINE_TOO_SMALL, ("Machine %s holds %s kg, less than the "
                        + "declared %s kg. Choose two machines in the same slot, another slot, another branch "
                        + "or assisted laundry.").formatted(resource.code(),
                        capacity.stripTrailingZeros().toPlainString(), declaredLoadKg.stripTrailingZeros().toPlainString()));
            }
        }
    }

    /**
     * BR-27 / SC-47: book-later orders may take at most {@code app.booking.advance-quota-percent} of a slot's usable
     * machines of each type (at least one, so a one-machine branch can still take bookings); the rest
     * is kept for use-now customers. Runs under the resource locks of the hold.
     */
    private void requireAdvanceQuota(ServiceConfig config, SlotPlan plan) {
        for (AllocationPlan allocation : plan.allocationPlans()) {
            String type = allocation.type();
            if (!"WASHER".equals(type) && !"DRYER".equals(type)) {
                continue;
            }
            int usable = queries.resourcesFor(config.branchId(), type).size();
            int quota = Math.max(1, usable * props.advanceQuotaPercent() / 100);
            Integer taken = jdbc.queryForObject("""
                    SELECT COUNT(DISTINCT b.id)
                    FROM booking_resource_allocations a
                    JOIN scheduled_bookings b ON b.id = a.booking_id
                    WHERE b.branch_id = ? AND b.advance_booking AND a.active
                      AND a.resource_type = ?
                      AND (b.status <> 'HELD' OR b.hold_expires_at > NOW())
                      AND a.occupied_from < ? AND a.occupied_until > ?
                    """, Integer.class, config.branchId(), type,
                    OffsetDateTime.ofInstant(allocation.until(), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(allocation.from(), ZoneOffset.UTC));
            if (taken != null && taken >= quota) {
                throw BusinessException.of(ErrorCode.ADVANCE_QUOTA_REACHED,
                        "Advance bookings for this time are full; machines are kept for walk-in use. "
                                + "Choose another time or use the machine now.");
            }
        }
    }

    /**
     * Expires lapsed holds on the given (already locked) resources before a new hold is placed, so the
     * database no-overlap constraint (V44) never sees a hold that the expiry job has not swept yet.
     */
    private void expireStaleHolds(List<Resource> resources) {
        if (resources.isEmpty()) {
            return;
        }
        UUID[] resourceIds = resources.stream().map(Resource::id).distinct().toArray(UUID[]::new);
        List<UUID> transactions = jdbc.query("""
                UPDATE scheduled_bookings b SET status = 'EXPIRED', updated_at = NOW()
                WHERE b.status = 'HELD' AND b.hold_expires_at <= NOW()
                  AND EXISTS (SELECT 1 FROM booking_resource_allocations a
                              WHERE a.booking_id = b.id AND a.resource_id = ANY (?))
                RETURNING b.transaction_id
                """, (rs, row) -> rs.getObject(1, UUID.class), (Object) resourceIds);
        if (!transactions.isEmpty()) {
            Object[] ids = transactions.toArray();
            String placeholders = String.join(",", java.util.Collections.nCopies(ids.length, "?"));
            jdbc.update("UPDATE transactions SET status = 'EXPIRED', updated_at = NOW() WHERE id IN (%s) AND status = 'PENDING_PAYMENT'"
                    .formatted(placeholders), ids);
            walletPayments.reverse(transactions, "Hoàn phần ví: hết thời gian giữ chỗ");
        }
    }

    @Transactional
    public void expireBookingHolds() {
        List<UUID> expired = jdbc.query("""
                UPDATE scheduled_bookings SET status = 'EXPIRED', updated_at = NOW()
                WHERE status = 'HELD' AND hold_expires_at <= NOW()
                RETURNING transaction_id
                """, (rs, row) -> rs.getObject(1, UUID.class));
        if (expired.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(expired.size(), "?"));
        Object[] expiredIds = expired.toArray();
        jdbc.update("""
                UPDATE transactions SET status = 'EXPIRED', updated_at = NOW()
                WHERE id IN (%s) AND status = 'PENDING_PAYMENT'
                """.formatted(placeholders), expiredIds);
        walletPayments.reverse(expired, "Hoàn phần ví: hết thời gian giữ chỗ");
        log.info("Expired {} booking hold(s)", expired.size());
    }

    private BookingDto mapBooking(ResultSet rs) throws SQLException {
        List<SlotPhaseDto> timeline;
        List<RecommendedResourceDto> resources;
        try {
            timeline = objectMapper.readValue(rs.getString("timeline"),
                    new TypeReference<>() {});
            resources = objectMapper.readValue(rs.getString("booking_resources"),
                    new TypeReference<>() {});
        } catch (Exception e) {
            throw new SQLException("Invalid stored booking details", e);
        }
        TimestampValue start = new TimestampValue(rs.getTimestamp("start_time").toInstant());
        TimestampValue end = new TimestampValue(rs.getTimestamp("expected_end_time").toInstant());
        TimestampValue holdExpires = new TimestampValue(rs.getTimestamp("hold_expires_at").toInstant());
        return new BookingDto(
                rs.getObject("transaction_id", UUID.class),
                rs.getObject("booking_id", UUID.class),
                rs.getString("service_code"), rs.getString("service_name"),
                rs.getString("service_mode"), rs.getString("booking_status"),
                rs.getString("transaction_status"), rs.getObject("branch_id", UUID.class),
                rs.getString("branch_name"), rs.getString("branch_address"),
                rs.getString("branch_phone"), start.value(), end.value(), holdExpires.value(),
                rs.getBigDecimal("total_amount"), "VND", timeline, resources,
                programService.fromJson(rs.getString("programs")),
                rs.getObject("payment_id", UUID.class), rs.getString("payment_status"),
                rs.getString("payment_url"), rs.getString("qr_code"));
    }

    private record TimestampValue(Instant value) {}

    private void validateSelectedResources(UUID branchId, List<Resource> resources) {
        for (Resource resource : resources) {
            if (!"WASHER".equals(resource.type()) && !"DRYER".equals(resource.type())) continue;
            List<Boolean> valid = jdbc.query("""
                    SELECT branch_id = ? AND type = ? AND online = TRUE
                           AND deployment_status = 'ACTIVE'
                           AND status IN ('AVAILABLE', 'RESERVED', 'WAITING_START',
                                          'RUNNING', 'PAUSED', 'COMPLETED')
                    FROM machines WHERE id = ? FOR UPDATE
                    """, (rs, row) -> rs.getBoolean(1), branchId, resource.type(), resource.id());
            if (valid.isEmpty() || !valid.getFirst()) {
                throw BusinessException.of(ErrorCode.SLOT_TAKEN, "A selected machine is no longer available for this branch or service");
            }
        }
    }

    private void lockResources(List<Resource> resources) {
        resources.stream().distinct()
                .sorted(Comparator.comparing(Resource::type).thenComparing(r -> r.id().toString()))
                .forEach(resource -> {
                    if ("WASHER".equals(resource.type()) || "DRYER".equals(resource.type())) {
                        machineRepository.findByIdForReservation(resource.id())
                                .orElseThrow(() -> BusinessException.of(ErrorCode.SLOT_TAKEN, "A selected machine is no longer available"));
                    } else {
                        List<UUID> locked = jdbc.query("""
                                SELECT id FROM branch_service_resources
                                WHERE id = ? AND enabled = TRUE FOR UPDATE
                                """, (rs, row) -> rs.getObject(1, UUID.class), resource.id());
                        if (locked.isEmpty()) {
                            throw BusinessException.of(ErrorCode.SLOT_TAKEN, "A selected resource is no longer available");
                        }
                    }
                });
    }
}
