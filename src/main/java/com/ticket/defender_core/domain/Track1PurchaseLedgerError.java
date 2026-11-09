package com.ticket.defender_core.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;
import java.util.UUID;

/** 거절된 반입의 실패 감사 이력입니다. rowIndex가 null이면 배치 전체 오류이며 입력 원문은 저장하지 않습니다. */
@Entity @Immutable @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "track1_purchase_ledger_errors", indexes = @Index(name = "idx_t1_ledger_error_batch", columnList = "batch_id,row_index"))
public class Track1PurchaseLedgerError {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "batch_id", nullable = false, length = 36) private UUID batchId;
    @Column(name = "row_index") private Integer rowIndex;
    @Column(nullable = false, length = 64) private String code;

    public Track1PurchaseLedgerError(UUID batchId, Integer rowIndex, String code) {
        id = UUID.randomUUID();
        this.batchId = Objects.requireNonNull(batchId);
        this.rowIndex = rowIndex;
        this.code = Objects.requireNonNull(code);
    }
}
