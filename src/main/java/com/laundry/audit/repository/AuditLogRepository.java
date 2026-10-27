package com.laundry.audit.repository;

import com.laundry.audit.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByEntityTypeAndEntityIdOrderByOccurredAtAsc(String entityType, String entityId);

    Page<AuditLog> findAllByOrderByOccurredAtDesc(Pageable pageable);

    Page<AuditLog> findByBranchIdOrderByOccurredAtDesc(UUID branchId, Pageable pageable);
}
