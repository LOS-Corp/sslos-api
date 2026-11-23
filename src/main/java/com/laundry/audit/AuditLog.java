package com.laundry.audit;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Nhật ký không sửa được (trigger chặn UPDATE/DELETE ở DB). */
@Entity
@Table(name = "audit_logs")
@Immutable
@Getter
@Setter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Instant occurredAt;
    private String actorType;
    private UUID actorUserId;
    private String actorRef;
    private String action;
    private String entityType;
    private String entityId;
    private String reason;
    @JdbcTypeCode(SqlTypes.JSON)
    private String beforeData;
    @JdbcTypeCode(SqlTypes.JSON)
    private String afterData;
    private String ipAddress;
    private String userAgent;
}
