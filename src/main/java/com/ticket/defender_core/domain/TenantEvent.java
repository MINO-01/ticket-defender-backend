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
@Table(name = "events",
        uniqueConstraints = @UniqueConstraint(name = "uk_events_tenant_external", columnNames = {"tenant_id", "external_event_ref"}),
        indexes = @Index(name = "idx_events_tenant_id", columnList = "tenant_id,id", unique = true))
public class TenantEvent {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", nullable = false, length = 36)
    private UUID tenantId;

    @Column(name = "external_event_ref", nullable = false, length = 128)
    private String externalEventRef;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "public_intake_enabled", nullable = false)
    private boolean publicIntakeEnabled;

    public TenantEvent(UUID id, UUID tenantId, String externalEventRef, String name, AccessRecordStatus status) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.externalEventRef = Objects.requireNonNull(externalEventRef);
        this.name = Objects.requireNonNull(name);
        this.status = Objects.requireNonNull(status).name();
    }

    public void setPublicIntakeEnabled(boolean enabled) {
        this.publicIntakeEnabled = enabled;
    }
}
