package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "memberships",
        uniqueConstraints = @UniqueConstraint(name = "uk_memberships_tenant_subject", columnNames = {"tenant_id", "subject"}),
        indexes = @Index(name = "idx_memberships_tenant_id", columnList = "tenant_id,id", unique = true))
public class AdminMembershipEntity {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", nullable = false, length = 36)
    private UUID tenantId;

    @Column(nullable = false, length = 191)
    private String subject;

    @Column(nullable = false, length = 24)
    private String status;

    public AdminMembershipEntity(UUID id, UUID tenantId, String subject, AccessRecordStatus status) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.subject = Objects.requireNonNull(subject);
        this.status = Objects.requireNonNull(status).name();
    }
}
