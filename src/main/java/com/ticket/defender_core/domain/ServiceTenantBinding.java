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

import java.util.Objects;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "service_tenant_bindings", uniqueConstraints = @UniqueConstraint(
        name = "uk_service_tenant_identity", columnNames = {"issuer", "client_id", "subject"}))
public class ServiceTenantBinding {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 36)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String issuer;

    @Column(name = "client_id", nullable = false, length = 191)
    private String clientId;

    @Column(nullable = false, length = 191)
    private String subject;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", nullable = false, length = 36)
    private UUID tenantId;

    @Column(nullable = false, length = 24)
    private String status;

    public ServiceTenantBinding(UUID id, String issuer, String clientId, String subject,
            UUID tenantId, AccessRecordStatus status) {
        this.id = Objects.requireNonNull(id);
        this.issuer = Objects.requireNonNull(issuer);
        this.clientId = Objects.requireNonNull(clientId);
        this.subject = Objects.requireNonNull(subject);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.status = Objects.requireNonNull(status).name();
    }
}
