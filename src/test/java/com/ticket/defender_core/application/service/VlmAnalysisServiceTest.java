package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.ReservationRepository;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import com.ticket.defender_core.domain.EvidenceType;
import com.ticket.defender_core.domain.Reservation;
import com.ticket.defender_core.domain.TicketAudit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VlmAnalysisServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private TicketAuditRepository ticketAuditRepository;

    @InjectMocks
    private VlmAnalysisService vlmAnalysisService;

    @Test
    @DisplayName("[방어 로직] 매핑 스코어가 0.85 미만이면 DB 조회를 아예 수행하지 않고 무시한다")
    void dropWhenScoreIsLow() {
        VlmAnalysisService.VlmParsedData lowScoreData = new VlmAnalysisService.VlmParsedData(
                "A", "1", "1", 0.84, "reporter123", List.of("url1")
        );

        // when
        vlmAnalysisService.processVlmReport(lowScoreData);

        // then
        verify(reservationRepository, never()).findByZoneAndRowNumAndSeatNum(anyString(), anyString(), anyString());
        verify(ticketAuditRepository, never()).save(any(TicketAudit.class));
    }

    @Test
    @DisplayName("[방어 로직] 제보된 좌석과 일치하는 DB 예매 내역이 없으면 저장을 수행하지 않는다")
    void dropWhenNoReservationFound() {
        // given
        VlmAnalysisService.VlmParsedData validData = new VlmAnalysisService.VlmParsedData(
                "VIP", "10", "5", 0.90, "reporter123", List.of("url1")
        );
        when(reservationRepository.findByZoneAndRowNumAndSeatNum("VIP", "10", "5"))
                .thenReturn(Optional.empty());

        // when
        vlmAnalysisService.processVlmReport(validData);

        // then
        verify(reservationRepository, times(1)).findByZoneAndRowNumAndSeatNum("VIP", "10", "5");
        verify(ticketAuditRepository, never()).save(any(TicketAudit.class));
    }

    @Test
    @DisplayName("[핵심 로직] 정상적인 데이터 인입 시, 도메인 객체를 생성하여 DB에 적발 내역을 저장한다")
    void saveAuditSuccessfully() {
        // given
        List<String> images = List.of("http://s3.../img1.png", "http://s3.../img2.png");
        VlmAnalysisService.VlmParsedData parsedData = new VlmAnalysisService.VlmParsedData(
                "R", "1", "1", 0.99, "hero_fan", images
        );

        Reservation mockReservation = mock(Reservation.class);
        when(mockReservation.getReservationNo()).thenReturn("RES-9999");
        when(mockReservation.getAccountId()).thenReturn("hacker_001");

        when(reservationRepository.findByZoneAndRowNumAndSeatNum("R", "1", "1"))
                .thenReturn(Optional.of(mockReservation));

        // when
        vlmAnalysisService.processVlmReport(parsedData);

        // then
        ArgumentCaptor<TicketAudit> captor = ArgumentCaptor.forClass(TicketAudit.class);
        verify(ticketAuditRepository, times(1)).save(captor.capture());

        TicketAudit savedAudit = captor.getValue();

        assertThat(savedAudit.getReservationNo()).isEqualTo("RES-9999");
        assertThat(savedAudit.getAccountId()).isEqualTo("hacker_001");
        assertThat(savedAudit.getReporterId()).isEqualTo("hero_fan");
        assertThat(savedAudit.getMappingScore()).isEqualTo(0.99);
        assertThat(savedAudit.getEvidenceType()).isEqualTo(EvidenceType.FAN_REPORT);

        assertThat(savedAudit.getEvidenceImageUrls()).hasSize(2);
        assertThat(savedAudit.getEvidenceImageUrls()).containsExactly("http://s3.../img1.png", "http://s3.../img2.png");
    }
}