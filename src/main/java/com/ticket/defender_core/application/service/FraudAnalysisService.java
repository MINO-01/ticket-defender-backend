package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.AgentAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.FastApiAdapter;
import com.ticket.defender_core.adapter.out.api.dto.FastApiClusterResponse;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisRequest;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import com.ticket.defender_core.domain.TicketAudit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudAnalysisService {

    private final FastApiAdapter fastApiAdapter;
    private final TicketAuditRepository ticketAuditRepository;

    public void processAgentData(AgentAnalysisRequest request) {

        List<String> paymentHashes = new ArrayList<>();
        List<String> addressHashes = new ArrayList<>();

        if (request.tickets() != null) {
            for (AgentAnalysisRequest.TicketHashData ticket : request.tickets()) {
                paymentHashes.add(ticket.paymentHash());
                addressHashes.add(ticket.addressHash());
            }
        }

        MacroAnalysisRequest macroRequest = new MacroAnalysisRequest(paymentHashes, addressHashes);
        List<FastApiClusterResponse> clusters = fastApiAdapter.requestMacroAnalysis(macroRequest);

        if (clusters == null || clusters.isEmpty()) {
            log.info("탐지된 암표 의심 군집이 없거나, 분석 서버 Fallback이 작동했습니다.");
            return;
        }

        saveFraudClusters(clusters, request);
    }

    @Transactional
    protected void saveFraudClusters(List<FastApiClusterResponse> clusters, AgentAnalysisRequest request) {

        Set<String> fraudulentPaymentHashes = new HashSet<>();

        for (FastApiClusterResponse cluster : clusters) {
            if (cluster != null && cluster.fraudulentPaymentHashes() != null) {
                fraudulentPaymentHashes.addAll(cluster.fraudulentPaymentHashes());
            }
        }

        List<TicketAudit> existingAudits = ticketAuditRepository.findByPaymentHashIn(new ArrayList<>(fraudulentPaymentHashes));

        Set<String> existingPairs = new HashSet<>();
        for (TicketAudit audit : existingAudits) {
            existingPairs.add(audit.getPaymentHash() + ":" + audit.getAccountId());
        }

        List<TicketAudit> newAudits = new ArrayList<>();

        for (AgentAnalysisRequest.TicketHashData ticket : request.tickets()) {

            if (fraudulentPaymentHashes.contains(ticket.paymentHash())) {
                String pairKey = ticket.paymentHash() + ":" + ticket.accountId();

                if (!existingPairs.contains(pairKey)) {
                    TicketAudit audit = TicketAudit.createMacroAudit(
                            ticket.accountId(),
                            ticket.paymentHash(),
                            ticket.addressHash()
                    );

                    newAudits.add(audit);
                    existingPairs.add(pairKey);
                }
            }
        }

        if (!newAudits.isEmpty()) {
            try {
                ticketAuditRepository.saveAll(newAudits);
                log.info("총 {}건의 매크로 의심 계정이 DB에 성공적으로 적재되었습니다.", newAudits.size());
            } catch (DataIntegrityViolationException e) {
                log.warn("DB 고유 제약 조건 위반 처리를 무시하고 넘어갑니다.");
            }
        }
    }
}