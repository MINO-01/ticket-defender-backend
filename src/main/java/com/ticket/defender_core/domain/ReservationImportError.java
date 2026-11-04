package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;
import java.util.UUID;

/** 원문 또는 예매번호를 저장하지 않는 행 수준 검증 오류입니다. */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "reservation_import_errors", uniqueConstraints =
        @UniqueConstraint(name = "uk_reservation_import_error", columnNames = {"batch_id", "row_index", "code"}))
public class ReservationImportError {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "batch_id", nullable = false, length = 36)
    private UUID batchId;
    @Column(name = "row_index", nullable = false)
    private int rowIndex;
    @Column(name = "code", nullable = false, length = 64)
    private String code;

    public ReservationImportError(UUID batchId, int rowIndex, String code) {
        this.id = UUID.randomUUID();
        this.batchId = Objects.requireNonNull(batchId);
        if (rowIndex < 0) throw new IllegalArgumentException("rowIndex must be non-negative");
        this.rowIndex = rowIndex;
        if (code == null || code.isBlank() || code.length() > 64) throw new IllegalArgumentException("Invalid code");
        this.code = code;
    }
}
