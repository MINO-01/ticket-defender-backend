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
@Table(name = "membership_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_membership_events_member_event", columnNames = {"membership_id", "event_id"}),
        indexes = {
                @Index(name = "idx_membership_events_tenant_member", columnList = "tenant_id,membership_id"),
                @Index(name = "idx_membership_events_tenant_event", columnList = "tenant_id,event_id")
        })
public class MembershipEventGrant {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "membership_id", nullable = false, length = 36)
    private UUID membershipId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", nullable = false, length = 36)
    private UUID tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", nullable = false, length = 36)
    private UUID eventId;

    public MembershipEventGrant(UUID id, UUID membershipId, UUID tenantId, UUID eventId) {
        this.id = Objects.requireNonNull(id);
        this.membershipId = Objects.requireNonNull(membershipId);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.eventId = Objects.requireNonNull(eventId);
    }
}
