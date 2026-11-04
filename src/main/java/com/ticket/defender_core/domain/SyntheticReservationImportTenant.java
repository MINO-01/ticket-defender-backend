package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** 합성 격리 환경에서만 예매 적재를 허용하는 서버 소유 tenant 등록부입니다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "synthetic_reservation_import_tenants")
public class SyntheticReservationImportTenant {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", nullable = false, length = 36, updatable = false)
    private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "isolation_id", nullable = false, length = 36, updatable = false)
    private UUID isolationId;
    @Column(name = "registered_at", nullable = false, updatable = false)
    private LocalDateTime registeredAt;

    public SyntheticReservationImportTenant(UUID tenantId, UUID isolationId, LocalDateTime registeredAt) {
        this.tenantId = Objects.requireNonNull(tenantId);
        this.isolationId = Objects.requireNonNull(isolationId);
        this.registeredAt = Objects.requireNonNull(registeredAt);
    }
}
