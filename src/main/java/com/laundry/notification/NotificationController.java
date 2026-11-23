package com.laundry.notification;

import com.laundry.common.PageResponse;
import com.laundry.security.Actor;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Thông báo của tôi")
public class NotificationController {

    private final NotificationService service;

    public record NotificationView(UUID id, Notification.Type type, UUID orderId, String payload, Instant at,
                                   Instant readAt) {
        static NotificationView of(Notification n) {
            return new NotificationView(n.getId(), n.getType(), n.getOrderId(), n.getPayload(), n.getScheduledAt(),
                    n.getReadAt());
        }
    }

    @GetMapping("/api/notifications")
    public PageResponse<NotificationView> inbox(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "30") int size) {
        return PageResponse.of(service.inbox(Actor.userId(), page, size), NotificationView::of);
    }

    @PostMapping("/api/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID id) {
        service.markRead(id, Actor.userId());
    }

    @Component
    @ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
    @RequiredArgsConstructor
    public static class DeliveryJob {
        private final NotificationService service;

        @Scheduled(fixedDelay = 15_000)
        public void deliver() {
            service.deliverDue();
        }
    }
}
