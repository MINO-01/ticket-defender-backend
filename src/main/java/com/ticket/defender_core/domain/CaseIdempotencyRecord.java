package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/** One committed command result per scoped idempotency key. Never stores the key or request body. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "case_idempotency_records", uniqueConstraints = @UniqueConstraint(
        name = "uk_case_idempotency_scope_key",
        columnNames = {"tenant_id", "actor_membership_id", "operation", "target_case_id", "key_digest"}))
public class CaseIdempotencyRecord {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "id", length = 36, nullable = false)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "tenant_id", length = 36, nullable = false)
    private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "actor_membership_id", length = 36, nullable = false)
    private UUID actorMembershipId;
    @Column(name = "operation", length = 32, nullable = false)
    private String operation;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "target_case_id", length = 36, nullable = false)
    private UUID targetCaseId;
    @Column(name = "key_digest", length = 64, nullable = false)
    private String keyDigest;
    @Column(name = "request_digest", length = 64, nullable = false)
    private String requestDigest;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "result_case_id", length = 36, nullable = false)
    private UUID resultCaseId;
    @Column(name = "response_json", columnDefinition = "text", nullable = false)
    private String responseJson;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public CaseIdempotencyRecord(UUID tenantId, UUID actorMembershipId, String operation,
            UUID targetCaseId, String keyDigest, String requestDigest, UUID resultCaseId,
            String responseJson, LocalDateTime now) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.actorMembershipId = actorMembershipId;
        this.operation = operation;
        this.targetCaseId = targetCaseId;
        this.keyDigest = keyDigest;
        this.requestDigest = requestDigest;
        this.resultCaseId = resultCaseId;
        this.responseJson = responseJson;
        this.createdAt = now;
    }
}
