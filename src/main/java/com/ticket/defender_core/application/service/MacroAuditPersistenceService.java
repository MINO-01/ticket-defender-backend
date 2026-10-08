package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.api.dto.FastApiClusterResponse;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisResponse;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import com.ticket.defender_core.domain.EvidenceType;
import com.ticket.defender_core.domain.MacroAnalysisEvidence;
import com.ticket.defender_core.domain.TicketAudit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.ZoneOffset;

@Slf4j
@Service
@RequiredArgsConstructor
public class MacroAuditPersistenceService {

    private final TicketAuditRepository ticketAuditRepository;

    /** 분석 응답의 구성원을 원 요청 티켓과 대조한 뒤 매크로 감사 내역을 저장합니다. */
    @Transactional
    public void persistDetectedAudits(
            MacroAnalysisResponse response,
            MacroAnalysisRequest request
    ) {
        if (response.status() != MacroAnalysisResponse.Status.COMPLETED
                || !request.requestId().equals(response.requestId())) {
            throw new IllegalStateException("완료되지 않았거나 현재 요청과 연결되지 않은 분석 결과입니다.");
        }
        if (!"LOUVAIN".equals(response.algorithm())) {
            throw new IllegalStateException("계약된 Louvain 분석 결과가 아니므로 저장할 수 없습니다.");
        }
        if (response.analyzedAt() == null || response.algorithmVersion() == null
                || response.algorithmVersion().isBlank()) {
            throw new IllegalStateException("분석 출처 정보가 누락되어 결과를 저장할 수 없습니다.");
        }
        if (response.clusters() == null) {
            throw new IllegalStateException("완료된 분석 응답에 군집 목록이 없습니다.");
        }

        Map<TicketKey, MacroAnalysisRequest.Ticket> requestedTickets = indexRequestedTickets(request.tickets());
        Map<TicketKey, ClusterMember> detectedMembers = indexDetectedMembers(response.clusters(), requestedTickets);
        if (detectedMembers.isEmpty()) {
            log.info("저장할 매크로 군집 구성원이 없습니다. 요청 ID: {}", response.requestId());
            return;
        }

        List<String> eventIds = detectedMembers.keySet().stream()
                .map(TicketKey::eventId)
                .distinct()
                .toList();
        List<String> reservationNos = detectedMembers.keySet().stream()
                .map(TicketKey::reservationNo)
                .distinct()
                .toList();

        Set<TicketKey> existingMacroAudits = new HashSet<>();
        ticketAuditRepository.findAllByEventIdInAndReservationNoIn(eventIds, reservationNos)
                .stream()
                .filter(audit -> audit.getEvidenceType() == EvidenceType.MACRO_GRAPH)
                .map(audit -> new TicketKey(audit.getAccountId(), audit.getReservationNo(), audit.getEventId()))
                .filter(detectedMembers::containsKey)
                .forEach(existingMacroAudits::add);

        List<TicketAudit> newAudits = new ArrayList<>();
        for (Map.Entry<TicketKey, ClusterMember> entry : detectedMembers.entrySet()) {
            if (existingMacroAudits.contains(entry.getKey())) {
                continue;
            }

            MacroAnalysisRequest.Ticket ticket = requestedTickets.get(entry.getKey());
            FastApiClusterResponse cluster = entry.getValue().cluster();
            MacroAnalysisEvidence evidence = new MacroAnalysisEvidence(
                    ticket.accountId(),
                    ticket.reservationNo(),
                    ticket.eventId(),
                    ticket.paymentHash(),
                    ticket.addressHash(),
                    ticket.deviceIdHash(),
                    ticket.ipHash(),
                    cluster.clusterId(),
                    cluster.riskScore(),
                    response.algorithm(),
                    response.algorithmVersion(),
                    response.requestId(),
                    response.analyzedAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
            );
            newAudits.add(TicketAudit.createMacroAudit(evidence));
        }

        if (!newAudits.isEmpty()) {
            ticketAuditRepository.saveAll(newAudits);
            log.info("그래프 분석 결과 {}건을 감사 내역으로 저장했습니다. 요청 ID: {}",
                    newAudits.size(), response.requestId());
        } else {
            log.info("이번 그래프 분석 결과는 이미 감사 내역에 반영되어 있습니다. 요청 ID: {}",
                    response.requestId());
        }
    }

    private Map<TicketKey, MacroAnalysisRequest.Ticket> indexRequestedTickets(
            List<MacroAnalysisRequest.Ticket> tickets
    ) {
        Map<TicketKey, MacroAnalysisRequest.Ticket> indexed = new HashMap<>();
        for (MacroAnalysisRequest.Ticket ticket : tickets) {
            TicketKey key = TicketKey.from(ticket);
            if (indexed.putIfAbsent(key, ticket) != null) {
                throw new IllegalStateException("요청에 동일한 공연·예매·계정 조합이 중복 포함되어 있습니다.");
            }
        }
        return indexed;
    }

    private Map<TicketKey, ClusterMember> indexDetectedMembers(
            List<FastApiClusterResponse> clusters,
            Map<TicketKey, MacroAnalysisRequest.Ticket> requestedTickets
    ) {
        Map<TicketKey, ClusterMember> indexed = new LinkedHashMap<>();
        for (FastApiClusterResponse cluster : clusters) {
            for (FastApiClusterResponse.Member member : cluster.members()) {
                TicketKey key = TicketKey.from(member);
                if (!requestedTickets.containsKey(key)) {
                    throw new IllegalStateException("분석 응답에 이번 요청에 포함되지 않은 예매 내역이 있습니다.");
                }
                if (indexed.putIfAbsent(key, new ClusterMember(cluster)) != null) {
                    throw new IllegalStateException("분석 응답에서 동일 예매 내역이 여러 군집에 포함되어 있습니다.");
                }
            }
        }
        return indexed;
    }

    private record TicketKey(String accountId, String reservationNo, String eventId) {
        private static TicketKey from(MacroAnalysisRequest.Ticket ticket) {
            return new TicketKey(ticket.accountId(), ticket.reservationNo(), ticket.eventId());
        }

        private static TicketKey from(FastApiClusterResponse.Member member) {
            return new TicketKey(member.accountId(), member.reservationNo(), member.eventId());
        }
    }

    private record ClusterMember(FastApiClusterResponse cluster) {}
}
