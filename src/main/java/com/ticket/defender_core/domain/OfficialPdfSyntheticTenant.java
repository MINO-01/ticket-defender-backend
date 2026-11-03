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

/**
 * 공식 PDF 합성 격리 발급을 허용할 고객사 등록부입니다.
 *
 * <p>공개 API로 만들 수 없고 격리 환경을 준비하는 서버 소유 fixture나 SQL로만 등록합니다. 등록 행의
 * {@code isolationId}가 서버 설정의 격리 식별자와 같고, Case와 출처 증거가 {@code registeredAt} 이후에
 * 생성된 경우에만 합성 범위로 인정합니다. 이 행만으로 데이터의 합성 여부가 증명되지는 않습니다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "official_pdf_synthetic_tenants")
public class OfficialPdfSyntheticTenant {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", nullable = false, length = 36, updatable = false)
    private UUID tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "isolation_id", nullable = false, length = 36, updatable = false)
    private UUID isolationId;

    @Column(name = "registered_at", nullable = false, updatable = false)
    private LocalDateTime registeredAt;

    public OfficialPdfSyntheticTenant(UUID tenantId, UUID isolationId, LocalDateTime registeredAt) {
        this.tenantId = Objects.requireNonNull(tenantId);
        this.isolationId = Objects.requireNonNull(isolationId);
        this.registeredAt = Objects.requireNonNull(registeredAt);
    }
}
