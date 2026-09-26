package com.ticket.defender_core.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.Objects;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "reservation")
public class Reservation {

    @Id
    private String reservationNo;
    private String accountId;
    private String zone;
    private String rowNum;
    private String seatNum;
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
