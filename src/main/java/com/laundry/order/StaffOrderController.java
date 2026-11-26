package com.laundry.order;

import com.laundry.common.PageResponse;
import com.laundry.common.error.BusinessException;
import com.laundry.refund.Refund;
import com.laundry.refund.RefundService;
import com.laundry.store.StoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Nhân viên / chủ cửa hàng: theo dõi đơn, xử lý sự cố, hoàn tiền. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Nhân viên · Đơn và hoàn tiền")
public class StaffOrderController {

    private final OrderRepository orders;
    private final OrderViews views;
    private final BookingService booking;
    private final CheckInService checkIn;
    private final StaffOrderService staff;
    private final RefundService refunds;
    private final StoreService store;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public record ReasonBody(@NotBlank String reason) {
    }

    public record CancelBody(boolean storeFault, @NotBlank String reason) {
    }

    public record StopBody(boolean storeFault, @NotBlank String reason) {
    }

    public record MachineBody(@NotNull UUID machineId, @NotBlank String reason) {
    }

    public record ExceptionRefundBody(@NotNull UUID orderId, @Min(1000) long amount, @NotBlank String reason) {
    }

    public record CompleteRefundBody(String transferReference, UUID proofAttachmentId) {
    }

    public record DetailView(OrderViews.OrderView order, List<OrderEventLog.EventView> history) {
    }

    @GetMapping("/api/staff/orders")
    @Operation(summary = "Đơn có giờ bắt đầu trong một ngày (giờ cửa hàng)")
    public List<OrderViews.OrderView> list(@RequestParam(required = false) LocalDate date,
                                           @RequestParam(required = false) Order.Status status) {
        LocalDate day = date != null ? date : LocalDate.now(store.settings().zone());
        Instant from = day.atStartOfDay(store.settings().zone()).toInstant();
        return orders.findInRange(status, from, from.plusSeconds(86_400)).stream().map(o -> views.view(o, false)).toList();
    }

    /**
     * Đơn có sự cố cần xử lý trong `days` ngày gần nhất: lượt bị gián đoạn (máy hỏng giữa chừng), lượt kích hoạt hết
     * hạn mà máy chưa chạy, hoặc có khoản hoàn chờ duyệt / chờ chuyển.
     */
    @GetMapping("/api/staff/orders/attention")
    @Operation(summary = "Đơn có sự cố cần xử lý: lượt gián đoạn, kích hoạt hết hạn, hoàn tiền đang chờ")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<OrderViews.OrderView> attention(@RequestParam(defaultValue = "7") int days) {
        Instant since = Instant.now().minusSeconds(86_400L * Math.max(1, Math.min(days, 60)));
        List<UUID> ids = jdbc.queryForList("""
                select distinct o.id from orders o
                left join order_legs l on l.order_id = o.id
                left join refunds r on r.order_id = o.id
                where o.updated_at >= ? and (
                      l.status = 'INTERRUPTED'
                   or (l.status = 'ACTIVATED' and l.activation_expires_at < now())
                   or r.status in ('PENDING_APPROVAL', 'APPROVED'))
                limit 200
                """, UUID.class, java.sql.Timestamp.from(since));
        return orders.findAllById(ids).stream()
                .sorted(java.util.Comparator.comparing(Order::getScheduledStartAt).reversed())
                .map(o -> views.view(o, false)).toList();
    }

    @GetMapping("/api/staff/orders/{id}")
    public DetailView get(@PathVariable UUID id) {
        Order o = orders.findById(id).orElseThrow(() -> BusinessException.notFound("đơn"));
        return new DetailView(views.view(o, false), views.history(id));
    }

    @GetMapping("/api/staff/orders/by-code/{code}")
    public DetailView byCode(@PathVariable String code) {
        Order o = orders.findByCode(code.trim().toUpperCase()).orElseThrow(() -> BusinessException.notFound("đơn"));
        return new DetailView(views.view(o, false), views.history(o.getId()));
    }

    @PostMapping("/api/staff/orders/{id}/activate")
    @Operation(summary = "Kích hoạt máy thủ công khi kiosk lỗi (§12), bắt buộc lý do")
    public KioskController.ScanView activate(@PathVariable UUID id, @Valid @RequestBody ReasonBody body) {
        CheckInService.Result r = checkIn.staffActivate(id, body.reason());
        return new KioskController.ScanView(r.outcome(), r.message(), r.machineCode(), r.until(),
                views.view(r.order(), false), OrderViews.IntentView.of(r.payment()), r.alternatives());
    }

    @PostMapping("/api/staff/orders/{id}/cancel")
    @Operation(summary = "Hủy đơn; storeFault = lỗi cửa hàng: hoàn 100%, không tính vi phạm")
    public OrderViews.OrderView cancel(@PathVariable UUID id, @Valid @RequestBody CancelBody body) {
        return views.view(booking.cancelByStaff(id, body.storeFault(), body.reason()), false);
    }

    @PostMapping("/api/staff/orders/{id}/legs/{legNo}/stop")
    @Operation(summary = "Dừng máy của khách; storeFault: lượt bị gián đoạn, cho chạy lại hoặc hoàn 100%")
    public OrderViews.OrderView stop(@PathVariable UUID id, @PathVariable int legNo, @Valid @RequestBody StopBody body) {
        return views.view(staff.stop(id, legNo, body.storeFault(), body.reason()), false);
    }

    @PostMapping("/api/staff/orders/{id}/legs/{legNo}/rerun")
    @Operation(summary = "Máy hỏng giữa chừng: chạy lại miễn phí trên máy khác cùng loại")
    public OrderViews.OrderView rerun(@PathVariable UUID id, @PathVariable int legNo, @Valid @RequestBody MachineBody body) {
        return views.view(staff.rerun(id, legNo, body.machineId(), body.reason()), false);
    }

    @PostMapping("/api/staff/orders/{id}/legs/{legNo}/refund")
    @Operation(summary = "Hoàn 100% giá lượt bị gián đoạn do sự cố cửa hàng")
    public OrderViews.OrderView refundLeg(@PathVariable UUID id, @PathVariable int legNo, @Valid @RequestBody ReasonBody body) {
        return views.view(staff.refundLeg(id, legNo, body.reason()), false);
    }

    @PostMapping("/api/staff/orders/{id}/legs/{legNo}/switch-machine")
    public OrderViews.OrderView switchMachine(@PathVariable UUID id, @PathVariable int legNo,
                                              @Valid @RequestBody MachineBody body) {
        return views.view(staff.switchMachine(id, legNo, body.machineId(), body.reason()), false);
    }

    // ---- Hoàn tiền ----

    @PostMapping("/api/staff/refunds")
    @Operation(summary = "Hoàn ngoại lệ (khiếu nại...): đến hạn mức nhân viên tự duyệt, trên hạn mức chờ chủ cửa hàng")
    public OrderViews.RefundView exception(@Valid @RequestBody ExceptionRefundBody body) {
        return OrderViews.RefundView.of(refunds.exception(body.orderId(), body.amount(), body.reason()));
    }

    @GetMapping("/api/staff/refunds")
    @Operation(summary = "Khoản hoàn chờ chuyển khoản / trả tại quầy (APPROVED) hoặc chờ duyệt")
    public PageResponse<OrderViews.RefundView> refunds(@RequestParam(defaultValue = "APPROVED") Set<Refund.Status> status,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(refunds.byStatus(status, page, size), OrderViews.RefundView::of);
    }

    @PostMapping("/api/staff/refunds/{id}/complete")
    @Operation(summary = "Đã chuyển khoản / trả tại quầy cho khách vãng lai")
    public OrderViews.RefundView complete(@PathVariable UUID id, @RequestBody CompleteRefundBody body) {
        return OrderViews.RefundView.of(refunds.complete(id, body.transferReference(), body.proofAttachmentId()));
    }

    @PostMapping("/api/owner/refunds/{id}/approve")
    public OrderViews.RefundView approve(@PathVariable UUID id) {
        return OrderViews.RefundView.of(refunds.approve(id));
    }

    @PostMapping("/api/owner/refunds/{id}/reject")
    public OrderViews.RefundView reject(@PathVariable UUID id, @Valid @RequestBody ReasonBody body) {
        return OrderViews.RefundView.of(refunds.reject(id, body.reason()));
    }

    @GetMapping("/api/owner/refunds")
    public PageResponse<OrderViews.RefundView> pending(@RequestParam(defaultValue = "PENDING_APPROVAL") Set<Refund.Status> status,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(refunds.byStatus(status, page, size), OrderViews.RefundView::of);
    }
}
