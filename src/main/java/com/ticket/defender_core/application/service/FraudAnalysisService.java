package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.AgentAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.FastApiAdapter;
import com.ticket.defender_core.adapter.out.api.dto.FastApiClusterResponse;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import com.ticket.defender_core.domain.TicketAudit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudAnalysisService {

    private final FastApiAdapter fastApiAdapter;
    private final TicketAuditRepository ticketAuditRepository;

    public void processAgentData(AgentAnalysisRequest request) {
        FastApiClusterResponse response = fastApiAdapter.requestFraudAnalysis(request);

        if (response.data() == null || response.data().isEmpty()) {
            log.info("탐지된 암표 의심 군집이 없거나, 분석 서버 Fallback이 작동했습니다.");
            return;
        }

        saveFraudClusters(response.data(), request);
    }

    @Transactional
    protected void saveFraudClusters(List<FastApiClusterResponse.ClusterData> clusters, AgentAnalysisRequest request) {
        Map<String, String> addressMap = request.tickets().stream()
                .collect(Collectors.toMap(
                        AgentAnalysisRequest.TicketHashData::accountId,
                        AgentAnalysisRequest.TicketHashData::addressHash,
                        (existing, replacement) -> existing
                ));

        List<String> receivedHashes = clusters.stream()
                .map(FastApiClusterResponse.ClusterData::payment_hash)
                .toList();

        Set<String> existingHashes = ticketAuditRepository.findExistingPaymentHashes(receivedHashes);
        List<TicketAudit> newAudits = new ArrayList<>();

        for (FastApiClusterResponse.ClusterData cluster : clusters) {
            log.info("의심 군집 적발 - 결제수단 해시: {}, 연결된 계정 수: {}",
                    cluster.getMaskedPaymentHash(), cluster.account_count());

            if (!existingHashes.contains(cluster.payment_hash())) {
                for (String accountId : cluster.accounts()) {
                    String actualAddressHash = addressMap.getOrDefault(accountId, "UNKNOWN_ADDR");

                    TicketAudit audit = new TicketAudit(accountId, cluster.payment_hash(), actualAddressHash);
                    audit.markAsFraud();
                    newAudits.add(audit);
                }
            }
        }

        if (!newAudits.isEmpty()) {
            try {
                ticketAuditRepository.saveAll(newAudits);
                log.info("총 {}건의 암표 의심 계정이 DB에 성공적으로 적재되었습니다.", newAudits.size());
            } catch (DataIntegrityViolationException e) {
                log.warn("DB 고유 제약 조건 위반 (동시성 요청에 의한 중복). 처리를 무시하고 넘어갑니다.");
            }
        }
    }
}
