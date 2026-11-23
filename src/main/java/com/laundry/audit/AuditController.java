package com.laundry.audit;

import com.laundry.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;
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
    private final AuditNames names;

    /**
     * actorName: tên tài khoản (hoặc kiosk) đã làm; entityLabel: tên dễ đọc của đối tượng (mã máy, tên profile, mã đơn…).
     * Cả hai null khi không tra được (hệ thống, thiết bị, bản ghi đã xóa): giao diện hiện mã.
     */
    public record AuditView(Long id, Instant occurredAt, String actorType, UUID actorUserId, String actorRef,
                            String actorName, String action, String entityType, String entityId, String entityLabel,
                            String reason, String beforeData, String afterData) {
        static AuditView of(AuditLog l, AuditNames.Names n) {
            return new AuditView(l.getId(), l.getOccurredAt(), l.getActorType(), l.getActorUserId(), l.getActorRef(),
                    n.actor(l), l.getAction(), l.getEntityType(), l.getEntityId(), n.entity(l), l.getReason(),
                    l.getBeforeData(), l.getAfterData());
        }
    }

    @GetMapping({"/api/owner/audit-logs", "/api/admin/audit-logs"})
    @Operation(summary = "Nhật ký thao tác, mới nhất trước; kèm tên người làm và tên đối tượng")
    @Transactional(readOnly = true)
    public PageResponse<AuditView> search(@RequestParam(required = false) String entityType,
                                          @RequestParam(required = false) String entityId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        Page<AuditLog> result = service.search(entityType, entityId, page, size);
        AuditNames.Names n = names.of(result.getContent());
        return PageResponse.of(result, l -> AuditView.of(l, n));
    }
}
