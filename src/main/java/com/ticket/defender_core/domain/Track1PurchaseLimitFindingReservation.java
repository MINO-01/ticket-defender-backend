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

/** 조사 신호의 예매별 수량 근거입니다. */
@Entity @Immutable @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "track1_purchase_limit_finding_reservations", uniqueConstraints =
        @UniqueConstraint(name = "uk_t1_limit_finding_reservation", columnNames = {"finding_id", "reservation_ref_hmac"}))
public class Track1PurchaseLimitFindingReservation {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "finding_id", nullable = false, length = 36) private UUID findingId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "reservation_ref_hmac", nullable = false, length = 64) private String reservationRefHmac;
    @Column(name = "effective_ticket_quantity", nullable = false) private int effectiveTicketQuantity;

    public Track1PurchaseLimitFindingReservation(UUID findingId, String reservation, int quantity) {
        id = UUID.randomUUID();
        this.findingId = Objects.requireNonNull(findingId);
        reservationRefHmac = Objects.requireNonNull(reservation);
        if (quantity < 1) throw new IllegalArgumentException("invalid reservation quantity");
        effectiveTicketQuantity = quantity;
    }
}
