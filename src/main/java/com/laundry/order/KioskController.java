package com.laundry.order;

import com.laundry.common.error.BusinessException;
import com.laundry.machine.DryerTimePackRepository;
import com.laundry.machine.Machine;
import com.laundry.machine.MachineDtos;
import com.laundry.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Kiosk tại cửa hàng (header X-Kiosk-Key). 3 nút chính (§8): Quét QR đơn · Giặt / sấy ngay · Mua thêm phút sấy.
 */
@RestController
@RequestMapping("/api/kiosk")
@RequiredArgsConstructor
@Tag(name = "Kiosk")
public class KioskController {

    private final AvailabilityService availability;
    private final BookingService booking;
    private final CheckInService checkIn;
    private final DryerExtensionService extensions;
    private final OrderRepository orders;
    private final OrderLegRepository legs;
    private final DryerTimePackRepository packs;
    private final OrderViews views;

    public record WalkInBody(@NotNull Order.Service service, UUID washerId, UUID dryerId, @Size(max = 16) String phone) {
    }

    public record ScanBody(String qrToken, String orderCode, String phoneLast4) {
    }

    public record SwitchBody(@NotNull UUID machineId) {
    }

    public record ExtensionBody(int legNo, @NotNull UUID packId) {
    }

    public record WalkInView(OrderViews.OrderView order, OrderViews.IntentView payment) {
    }

    public record ScanView(CheckInService.Outcome outcome, String message, String machineCode, Instant until,
                           OrderViews.OrderView order, OrderViews.IntentView payment,
                           List<CheckInService.Alternative> alternatives) {
    }

    public record LookupView(OrderViews.OrderView order, List<MachineDtos.PackView> dryerPacks, Integer runningDryerLegNo) {
    }

    public record ExtensionView(UUID extensionId, int minutes, long price, OrderViews.IntentView payment) {
    }

    @GetMapping("/machines")
    @Operation(summary = "Máy cho đặt ngay: rảnh ngay hoặc rảnh lúc HH:MM (chờ tối đa 30 phút)")
    public List<AvailabilityService.Option> machines(@RequestParam Order.Service service) {
        return availability.walkIn(service);
    }

    @PostMapping("/walk-in")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Đặt ngay: giữ máy 5 phút, trả 100% qua QR payOS. Tiền về thì kích hoạt máy (rảnh ngay)")
    public WalkInView walkIn(@Valid @RequestBody WalkInBody body) {
        BookingService.WalkIn w = booking.createWalkIn(Actor.kiosk().id(), body.service(), body.washerId(),
                body.dryerId(), body.phone());
        return new WalkInView(views.view(w.order(), true), OrderViews.IntentView.of(w.intent()));
    }

    @GetMapping("/orders/{id}")
    @Operation(summary = "Kiosk hỏi lại trạng thái (sau khi hiện QR thanh toán / kích hoạt)")
    public OrderViews.OrderView get(@PathVariable UUID id) {
        return views.view(find(id), false);
    }

    @PostMapping("/orders/{id}/cancel")
    @Operation(summary = "Hủy đơn đặt ngay: chưa trả miễn phí; đã trả mà máy chưa chạy hoàn 100%")
    public OrderViews.OrderView cancel(@PathVariable UUID id) {
        return views.view(booking.cancelAtKiosk(id), false);
    }

    @PostMapping("/scan")
    @Operation(summary = "Quét QR đơn / biên nhận (hoặc mã đơn + 4 số cuối SĐT): check-in, thu phần còn lại, kích hoạt máy")
    public ScanView scan(@RequestBody ScanBody body) {
        return view(checkIn.scan(Actor.kiosk().id(), body.qrToken(), body.orderCode(), body.phoneLast4()));
    }

    @PostMapping("/orders/{id}/switch-machine")
    @Operation(summary = "Đến sớm mà máy bận: chuyển sang máy cùng loại, cùng giá đang rảnh rồi kích hoạt")
    public ScanView switchMachine(@PathVariable UUID id, @Valid @RequestBody SwitchBody body) {
        return view(checkIn.switchMachine(Actor.kiosk().id(), id, body.machineId()));
    }

    @PostMapping("/lookup")
    @Operation(summary = "Quét QR để mua thêm phút sấy: trả đơn và các gói của máy sấy đang chạy")
    public LookupView lookup(@RequestBody ScanBody body) {
        Order o = checkIn.find(body.qrToken(), body.orderCode(), body.phoneLast4());
        OrderLeg dryer = legs.findByOrderIdOrderByLegNo(o.getId()).stream()
                .filter(l -> l.getMachineType() == Machine.Type.DRYER && l.getStatus() == OrderLeg.Status.RUNNING)
                .findFirst().orElse(null);
        List<MachineDtos.PackView> list = dryer == null ? List.of()
                : packs.findByMachineIdAndActiveTrueOrderByMinutesAsc(dryer.getMachineId()).stream()
                .map(p -> new MachineDtos.PackView(p.getId(), p.getMinutes(), p.getPriceVnd(), p.isActive())).toList();
        return new LookupView(views.view(o, false), list, dryer == null ? null : dryer.getLegNo());
    }

    @PostMapping("/orders/{id}/extensions")
    public ExtensionView extend(@PathVariable UUID id, @Valid @RequestBody ExtensionBody body) {
        DryerExtensionService.Purchase p = extensions.purchase(id, body.legNo() == 0 ? 1 : body.legNo(), body.packId(),
                false, null);
        return new ExtensionView(p.extension().getId(), p.extension().getMinutes(), p.extension().getPriceAmount(),
                OrderViews.IntentView.of(p.intent()));
    }

    private Order find(UUID id) {
        return orders.findById(id).orElseThrow(() -> BusinessException.notFound("đơn"));
    }

    private ScanView view(CheckInService.Result r) {
        return new ScanView(r.outcome(), r.message(), r.machineCode(), r.until(), views.view(r.order(), false),
                OrderViews.IntentView.of(r.payment()), r.alternatives());
    }
}
