package com.laundry.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.audit.entity.AuditLog;
import com.laundry.audit.repository.AuditLogRepository;
import com.laundry.shared.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Writes audit entries in the caller's transaction, so an action and its audit entry
 * commit or roll back together (NFR-14). Callers pass only non-sensitive fields:
 * never PINs, OTPs, passwords or full personal data (NFR-19, NFR-23).
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    static final String SYSTEM_ROLE = "SYSTEM";

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public void record(AuditEntry entry) {
        CurrentUser actor = entry.actor();
        repository.save(AuditLog.builder()
                .occurredAt(OffsetDateTime.now(clock))
                .actorId(actor == null ? null : actor.id())
                .actorRole(actor == null ? SYSTEM_ROLE : actor.primaryRole().name())
                .action(entry.action().name())
                .entityType(entry.entityType())
                .entityId(entry.entityId() == null ? null : entry.entityId().toString())
                .branchId(entry.branchId())
                .reason(entry.reason())
                .beforeState(json(entry.before()))
                .afterState(json(entry.after()))
                .build());
    }

    private String json(Map<String, ?> state) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Audit state is not serializable", e);
        }
    }

    /** UC-10: newest first; {@code branchId} narrows the list to one branch. */
    @Transactional(readOnly = true)
    public Page<AuditLogView> list(UUID branchId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<AuditLog> logs = branchId == null
                ? repository.findAllByOrderByOccurredAtDesc(pageable)
                : repository.findByBranchIdOrderByOccurredAtDesc(branchId, pageable);
        return logs.map(log -> new AuditLogView(log.getId(), log.getOccurredAt(), log.getActorId(), log.getActorRole(),
                log.getAction(), log.getEntityType(), log.getEntityId(), log.getBranchId(), log.getReason(),
                log.getBeforeState(), log.getAfterState()));
    }

    /** One audit entry; {@code before} and {@code after} are the stored JSON states. */
    public record AuditLogView(Long id, OffsetDateTime occurredAt, UUID actorId, String actorRole, String action,
                               String entityType, String entityId, UUID branchId, String reason, String before,
                               String after) {
    }

    /** What happened, to which entity, by whom (null actor = system job). */
    public record AuditEntry(AuditAction action, String entityType, Object entityId, UUID branchId,
                             CurrentUser actor, String reason, Map<String, ?> before, Map<String, ?> after) {

        public static AuditEntry of(AuditAction action, String entityType, Object entityId, UUID branchId,
                                    CurrentUser actor) {
            return new AuditEntry(action, entityType, entityId, branchId, actor, null, null, null);
        }

        public AuditEntry withReason(String value) {
            return new AuditEntry(action, entityType, entityId, branchId, actor, value, before, after);
        }

        public AuditEntry withChange(Map<String, ?> beforeState, Map<String, ?> afterState) {
            return new AuditEntry(action, entityType, entityId, branchId, actor, reason, beforeState, afterState);
        }
    }
}
