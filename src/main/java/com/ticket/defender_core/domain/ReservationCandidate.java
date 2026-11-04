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
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;
import java.util.UUID;

/** 후보 대조에 필요한 좌석과 opaque ID만 보존하는 합성 예매 행입니다. */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "reservation_candidates", uniqueConstraints =
        @UniqueConstraint(name = "uk_reservation_candidate_reference", columnNames = {"batch_id", "reservation_ref_hmac"}),
        indexes = @Index(name = "idx_reservation_candidate_seat", columnList = "batch_id,zone,row_num,seat_num"))
public class ReservationCandidate {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "batch_id", nullable = false, length = 36)
    private UUID batchId;
    @Column(name = "source_row_index", nullable = false)
    private int sourceRowIndex;
    @Column(name = "reservation_ref_hmac", nullable = false, length = 64)
    private String reservationRefHmac;
    @Column(name = "reference_hmac_key_version", nullable = false, length = 32)
    private String referenceHmacKeyVersion;
    @Column(name = "zone", nullable = false, length = 128)
    private String zone;
    @Column(name = "row_num", nullable = false, length = 128)
    private String rowNum;
    @Column(name = "seat_num", nullable = false, length = 128)
    private String seatNum;

    public ReservationCandidate(UUID batchId, int sourceRowIndex, String reservationRefHmac,
            String referenceHmacKeyVersion, String zone, String rowNum, String seatNum) {
        this.id = UUID.randomUUID();
        this.batchId = Objects.requireNonNull(batchId);
        if (sourceRowIndex < 0) throw new IllegalArgumentException("sourceRowIndex must be non-negative");
        this.sourceRowIndex = sourceRowIndex;
        this.reservationRefHmac = required(reservationRefHmac, 64);
        this.referenceHmacKeyVersion = required(referenceHmacKeyVersion, 32);
        this.zone = required(zone, 128);
        this.rowNum = required(rowNum, 128);
        this.seatNum = required(seatNum, 128);
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("Invalid value");
        return value;
    }
}
