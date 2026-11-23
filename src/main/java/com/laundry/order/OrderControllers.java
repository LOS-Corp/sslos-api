package com.laundry.order;

import com.laundry.common.PageResponse;
import com.laundry.common.error.BusinessException;
import com.laundry.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class OrderControllers {

    private OrderControllers() {
    }

    @RestController
    @RequiredArgsConstructor
    @Tag(name = "Công khai · Lịch trống")
    public static class AvailabilityController {
        private final AvailabilityService availability;

        @GetMapping("/api/public/availability")
        @Operation(summary = "Máy còn đặt trước được cho giờ bắt đầu T (đã tính khoảng giữ máy, giờ mở cửa, hạn mức 70%)")
        public AvailabilityService.AdvanceResult advance(@RequestParam Order.Service service, @RequestParam Instant start,
                                                         @RequestParam(required = false) UUID washerId) {
            return availability.advance(service, start, washerId, null);
        }

        @GetMapping("/api/public/availability/slots")
        @Operation(summary = "Các khung 15 phút của một ngày (giờ cửa hàng) và số máy còn trống")
        public List<AvailabilityService.Slot> slots(@RequestParam Order.Service service, @RequestParam LocalDate date) {
            return availability.slots(service, date);
        }
    }

    @RestController
    @RequiredArgsConstructor
    @Tag(name = "Khách · Đơn đặt trước")
    public static class CustomerOrderController {
        private final BookingService booking;
        private final DryerExtensionService extensions;
        private final OrderRepository orders;
        private final OrderViews views;

        public record CreateBody(@NotNull Order.Service service, @NotNull Instant startAt, UUID washerId, UUID dryerId) {
        }

        public record PayBody(@NotNull BookingService.PayOption option, boolean useWallet) {
        }

        public record RescheduleBody(@NotNull Instant startAt, UUID washerId, UUID dryerId) {
        }

        public record ExtensionBody(int legNo, @NotNull UUID packId, boolean useWallet) {
        }

        public record ExtensionView(UUID extensionId, int minutes, long price, OrderViews.IntentView payment) {
        }

        @PostMapping("/api/orders")
        @ResponseStatus(HttpStatus.CREATED)
        @Operation(summary = "Đặt trước: giữ chỗ 5 phút để thanh toán (tối thiểu tiền giữ chỗ 20%, hoặc 100%)")
        public OrderViews.OrderView create(@Valid @RequestBody CreateBody body) {
            Order o = booking.createAdvance(Actor.userId(), body.service(), body.startAt(), body.washerId(), body.dryerId());
            return views.view(o, true);
        }

        @GetMapping("/api/orders")
        public PageResponse<OrderViews.OrderView> mine(@RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
            return PageResponse.of(orders.findByCustomerIdOrderByCreatedAtDesc(Actor.userId(),
                    PageRequest.of(page, Math.min(size, 50))), o -> views.view(o, true));
        }

        @GetMapping("/api/orders/{id}")
        public OrderViews.OrderView get(@PathVariable UUID id) {
            return views.view(owned(id), true);
        }

        @PostMapping("/api/orders/{id}/payments")
        @Operation(summary = "Tạo phiếu thu: DEPOSIT / FULL lúc giữ chỗ, BALANCE trả nốt trước check-in. Ví trừ trước")
        public OrderViews.IntentView pay(@PathVariable UUID id, @Valid @RequestBody PayBody body) {
            return OrderViews.IntentView.of(booking.pay(Actor.userId(), id, body.option(), body.useWallet()));
        }

        @PostMapping("/api/orders/{id}/cancel")
        @Operation(summary = "Hủy: trước T−60' hoặc 10' sau khi đặt hoàn 100%; muộn hơn mất tiền giữ chỗ")
        public OrderViews.OrderView cancel(@PathVariable UUID id) {
            return views.view(booking.cancelByCustomer(Actor.userId(), id), true);
        }

        @PostMapping("/api/orders/{id}/reschedule")
        @Operation(summary = "Đổi giờ miễn phí 1 lần, trước T−60', máy cùng giá")
        public OrderViews.OrderView reschedule(@PathVariable UUID id, @Valid @RequestBody RescheduleBody body) {
            return views.view(booking.reschedule(Actor.userId(), id, body.startAt(), body.washerId(), body.dryerId()), true);
        }

        @PostMapping("/api/orders/{id}/extensions")
        @Operation(summary = "Mua thêm phút sấy khi máy sấy đang chạy")
        public ExtensionView extend(@PathVariable UUID id, @Valid @RequestBody ExtensionBody body) {
            DryerExtensionService.Purchase p = extensions.purchase(id, body.legNo() == 0 ? 1 : body.legNo(),
                    body.packId(), body.useWallet(), Actor.userId());
            return new ExtensionView(p.extension().getId(), p.extension().getMinutes(), p.extension().getPriceAmount(),
                    OrderViews.IntentView.of(p.intent()));
        }

        @GetMapping("/api/orders/{id}/history")
        public List<OrderEventLog.EventView> history(@PathVariable UUID id) {
            return views.history(owned(id).getId());
        }

        private Order owned(UUID id) {
            return orders.findById(id).filter(o -> Actor.userId().equals(o.getCustomerId()))
                    .orElseThrow(() -> BusinessException.notFound("đơn"));
        }
    }
}
