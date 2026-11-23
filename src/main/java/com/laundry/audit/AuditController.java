package com.laundry.audit;

import com.laundry.common.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Nhật ký thao tác")
public class AuditController {

    private final AuditService service;

    public record AuditView(Long id, Instant occurredAt, String actorType, UUID actorUserId, String actorRef,
                            String action, String entityType, String entityId, String reason,
                            String beforeData, String afterData) {
        static AuditView of(AuditLog l) {
            return new AuditView(l.getId(), l.getOccurredAt(), l.getActorType(), l.getActorUserId(), l.getActorRef(),
                    l.getAction(), l.getEntityType(), l.getEntityId(), l.getReason(), l.getBeforeData(),
                    l.getAfterData());
        }
    }

    @GetMapping({"/api/owner/audit-logs", "/api/admin/audit-logs"})
    public PageResponse<AuditView> search(@RequestParam(required = false) String entityType,
                                          @RequestParam(required = false) String entityId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(service.search(entityType, entityId, page, size), AuditView::of);
    }
}
