package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /**
     * idx_seat_location 복합 인덱스를 활용합니다.
     * 구역, 열, 좌석 번호로 수백만 건 중 단 한 명의 예매자(암표상)를 찾아냅니다.
     *
     * @param zone    예매 구역
     * @param rowNum  열 번호
     * @param seatNum 좌석 번호
     * @return 일치하는 예매 내역 (허위 제보이거나 이미 취소된 표인 경우 Optional.empty() 반환)
     *
     */
    Optional<Reservation> findByZoneAndRowNumAndSeatNum(String zone, String rowNum, String seatNum);
}
