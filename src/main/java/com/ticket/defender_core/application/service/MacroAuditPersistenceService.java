package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.api.dto.FastApiClusterResponse;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisResponse;
import com.ticket.defender_core.adapter.out.persistence.MacroAuditEvidenceRepository;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import com.ticket.defender_core.domain.MacroAnalysisEvidence;
import com.ticket.defender_core.domain.MacroAuditEvidence;
import com.ticket.defender_core.domain.MacroEvidenceKey;
import com.ticket.defender_core.domain.TicketAudit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 매크로 분석 결과를 감사 내역과 전용 증거 테이블에 저장합니다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroAuditPersistenceService {

    private final TicketAuditRepository ticketAuditRepository;
    private final MacroAuditEvidenceRepository macroAuditEvidenceRepository;

    /** 분석 결과를 요청과 대조하고 새로 적발된 내역만 저장합니다. */
    @Transactional
    public void persistDetectedAudits(
            MacroAnalysisResponse response,
            MacroAnalysisRequest request
    ) {
        validateAnalysisResponse(response, request);

        Map<TicketKey, MacroAnalysisRequest.Ticket> requestedTickets = indexRequestedTickets(request.tickets());
        Map<TicketKey, ClusterMember> detectedMembers = indexDetectedMembers(response.clusters(), requestedTickets);
        if (detectedMembers.isEmpty()) {
            log.info("저장할 매크로 군집 구성원이 없습니다. 요청 ID: {}", response.requestId());
            return;
        }

        Map<String, TicketKey> requestedKeysByHash = new HashMap<>();
        detectedMembers.keySet().forEach(ticketKey -> {
            String evidenceKey = keyFor(ticketKey);
            TicketKey previous = requestedKeysByHash.putIfAbsent(evidenceKey, ticketKey);
            if (previous != null && !previous.equals(ticketKey)) {
                throw new IllegalStateException("서로 다른 예매 식별 조합이 같은 매크로 증거 키를 생성했습니다.");
            }
        });

        Set<TicketKey> existingMacroAudits = findExistingMacroAudits(requestedKeysByHash);
        List<TicketAudit> newAudits = createNewAudits(
                detectedMembers, requestedTickets, existingMacroAudits, response);

        if (!newAudits.isEmpty()) {
            ticketAuditRepository.saveAll(newAudits);
            log.info("그래프 분석 결과 {}건을 감사 및 매크로 증거 내역으로 저장했습니다. 요청 ID: {}",
                    newAudits.size(), response.requestId());
        } else {
            log.info("이번 그래프 분석 결과는 이미 감사 내역에 반영되어 있습니다. 요청 ID: {}", response.requestId());
        }
    }

    /** 분석이 완료됐고 요청과 출처 정보가 맞는지 확인합니다. */
    private void validateAnalysisResponse(MacroAnalysisResponse response, MacroAnalysisRequest request) {
        if (response.status() != MacroAnalysisResponse.Status.COMPLETED
                || !request.requestId().equals(response.requestId())) {
            throw new IllegalStateException("완료되지 않았거나 현재 요청과 연결되지 않은 분석 결과입니다.");
        }
        if (!"LOUVAIN".equals(response.algorithm())) {
            throw new IllegalStateException("계약한 Louvain 분석 결과가 아니므로 저장할 수 없습니다.");
        }
        if (response.analyzedAt() == null || response.algorithmVersion() == null
                || response.algorithmVersion().isBlank()) {
            throw new IllegalStateException("분석 출처 정보가 누락되어 결과를 저장할 수 없습니다.");
        }
        if (response.clusters() == null) {
            throw new IllegalStateException("완료된 분석 응답에 군집 목록이 없습니다.");
        }
    }

    /** 요청 티켓을 식별 조합으로 묶고 중복을 확인합니다. */
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

    /** 응답 구성원이 요청에 포함됐는지 확인하고 군집별로 모읍니다. */
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

    /** 이미 저장된 매크로 증거를 키 목록으로 조회합니다. */
    private Set<TicketKey> findExistingMacroAudits(Map<String, TicketKey> requestedKeysByHash) {
        Set<TicketKey> existing = new HashSet<>();
        List<MacroAuditEvidence> evidenceRows = macroAuditEvidenceRepository
                .findAllByEvidenceKeyIn(requestedKeysByHash.keySet());

        for (MacroAuditEvidence evidence : evidenceRows) {
            TicketKey requestedKey = requestedKeysByHash.get(evidence.getEvidenceKey());
            TicketKey storedKey = new TicketKey(
                    evidence.getAccountId(), evidence.getReservationNo(), evidence.getEventId());
            if (requestedKey == null || !requestedKey.equals(storedKey)) {
                throw new IllegalStateException("기존 매크로 증거 키가 다른 예매 식별 조합과 충돌했습니다.");
            }
            existing.add(storedKey);
        }
        return existing;
    }

    /** 기존 증거가 없는 구성원으로 감사 내역을 만듭니다. */
    private List<TicketAudit> createNewAudits(
            Map<TicketKey, ClusterMember> detectedMembers,
            Map<TicketKey, MacroAnalysisRequest.Ticket> requestedTickets,
            Set<TicketKey> existingMacroAudits,
            MacroAnalysisResponse response
    ) {
        List<TicketAudit> newAudits = new ArrayList<>();
        for (Map.Entry<TicketKey, ClusterMember> entry : detectedMembers.entrySet()) {
            TicketKey key = entry.getKey();
            if (existingMacroAudits.contains(key)) {
                continue;
            }

            MacroAnalysisRequest.Ticket ticket = requestedTickets.get(key);
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
        return newAudits;
    }

    /** 공연·예매·계정 조합의 유니크 키를 만듭니다. */
    private String keyFor(TicketKey ticketKey) {
        return MacroEvidenceKey.create(ticketKey.eventId(), ticketKey.reservationNo(), ticketKey.accountId());
    }

    private record TicketKey(String accountId, String reservationNo, String eventId) {
        /** 요청 티켓의 식별 값을 묶습니다. */
        private static TicketKey from(MacroAnalysisRequest.Ticket ticket) {
            return new TicketKey(ticket.accountId(), ticket.reservationNo(), ticket.eventId());
        }

        /** 응답 구성원의 식별 값을 묶습니다. */
        private static TicketKey from(FastApiClusterResponse.Member member) {
            return new TicketKey(member.accountId(), member.reservationNo(), member.eventId());
        }
    }

    private record ClusterMember(FastApiClusterResponse cluster) {}
}
