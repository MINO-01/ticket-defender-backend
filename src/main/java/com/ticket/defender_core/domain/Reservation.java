package com.ticket.defender_core.domain;

import jakarta.persistence.*;
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
@Table(
        name = "reservation",
        indexes = {
                @Index(name = "idx_seat_location", columnList = "zone, row_num, seat_num"),
                @Index(name = "idx_reservation_scope", columnList = "tenant_id, event_id, performance_ref")
        }
)
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String reservationNo;

    /** 과거 예매 행에는 범위가 없으므로 null로 남기고 Track 2 조회에서 제외합니다. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", length = 36)
    private UUID tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", length = 36)
    private UUID eventId;

    @Column(name = "performance_ref", length = 128)
    private String performanceRef;

    @Column(nullable = false)
    private String accountId;

    @Column(nullable = false)
    private String zone;

    @Column(name = "row_num", nullable = false)
    private String rowNum;

    @Column(name = "seat_num", nullable = false)
    private String seatNum;

    @Column(nullable = false)
    private Long originalPrice;

    public Reservation(String reservationNo, String accountId, String zone, String rowNum, String seatNum, Long originalPrice) {

        Objects.requireNonNull(originalPrice, "originalPrice(정가)는 null일 수 없습니다.");

        this.reservationNo = reservationNo;
        this.accountId = accountId;
        this.zone = zone;
        this.rowNum = rowNum;
        this.seatNum = seatNum;
        this.originalPrice = originalPrice;
    }

    /** 인증된 고객사의 공연과 예매처가 부여한 회차 식별자를 가진 예매 내역입니다. */
    public Reservation(UUID tenantId, UUID eventId, String performanceRef, String reservationNo,
            String accountId, String zone, String rowNum, String seatNum, Long originalPrice) {
        this(reservationNo, accountId, zone, rowNum, seatNum, originalPrice);
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId는 필수입니다.");
        this.eventId = Objects.requireNonNull(eventId, "eventId는 필수입니다.");
        if (performanceRef == null || performanceRef.isBlank()) {
            throw new IllegalArgumentException("회차 식별자는 필수입니다.");
        }
        this.performanceRef = performanceRef;
    }

    // 방어해 낸 예상 피해 금액 (암표상의 부당 이익 원금)
    public Long calculateDefendedIllicitProfit(Long blackMarketPrice) {
        if (blackMarketPrice == null || blackMarketPrice <= this.originalPrice) {
            return 0L;
        }
        return blackMarketPrice - this.originalPrice;
    }

    //예상 최대 과징금 산출
    public Long calculateExpectedMaxPenalty(Long blackMarketPrice) {
        if (blackMarketPrice == null || blackMarketPrice <= 0) {
            return 0L;
        }
        return blackMarketPrice * 50;
    }
}
