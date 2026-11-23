package com.laundry.audit;

import com.laundry.security.Actor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Ghi nhật ký thao tác nhạy cảm (§2): ai, lúc nào, làm gì, vì sao, trước/sau.
 * Ghi trong cùng transaction với thao tác: thao tác rollback thì nhật ký cũng không còn.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** Người dùng đang đăng nhập làm; lý do bắt buộc (CHECK ở DB). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void user(String action, String entityType, String entityId, String reason, Object before, Object after) {
        AuditLog log = base(action, entityType, entityId, reason, before, after);
        Actor actor = Actor.current().orElse(null);
        if (actor instanceof Actor.User u) {
            log.setActorType("USER");
            log.setActorUserId(u.id());
        } else if (actor instanceof Actor.Kiosk k) {
            log.setActorType("KIOSK");
            log.setActorRef(k.id().toString());
        } else {
            log.setActorType("SYSTEM");
        }
        repository.save(log);
    }

    /** Hệ thống tự làm (job, quy tắc tự động, thiết bị). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void system(String action, String entityType, String entityId, String reason, Object before, Object after) {
        AuditLog log = base(action, entityType, entityId, reason, before, after);
        log.setActorType("SYSTEM");
        repository.save(log);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void device(String deviceId, String action, String entityType, String entityId, Object after) {
        AuditLog log = base(action, entityType, entityId, null, null, after);
        log.setActorType("DEVICE");
        log.setActorRef(deviceId);
        repository.save(log);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> search(String entityType, String entityId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "id"));
        if (entityType != null && entityId != null) {
            return repository.findByEntityTypeAndEntityId(entityType, entityId, pageable);
        }
        if (entityType != null) return repository.findByEntityType(entityType, pageable);
        return repository.findAll(pageable);
    }

    private AuditLog base(String action, String entityType, String entityId, String reason, Object before, Object after) {
        AuditLog log = new AuditLog();
        log.setOccurredAt(clock.instant());
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setReason(reason);
        log.setBeforeData(before == null ? null : mapper.writeValueAsString(before));
        log.setAfterData(after == null ? null : mapper.writeValueAsString(after));
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            log.setIpAddress(request.getRemoteAddr());
            String agent = request.getHeader("User-Agent");
            log.setUserAgent(agent == null ? null : agent.substring(0, Math.min(255, agent.length())));
        }
        return log;
    }
}
