package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 매크로 분석 결과를 팬 제보와 분리해 저장합니다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "macro_audit_evidence",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_macro_audit_evidence_key",
                columnNames = "evidence_key"
        )
)
public class MacroAuditEvidence {

    @Id
    @Column(name = "audit_id")
    private Long auditId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "audit_id", nullable = false)
    private TicketAudit ticketAudit;

    @Column(name = "evidence_key", nullable = false, length = 64)
    private String evidenceKey;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "reservation_no", nullable = false)
    private String reservationNo;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(name = "device_id_hash")
    private String deviceIdHash;

    @Column(name = "ip_hash")
    private String ipHash;

    @Column(name = "cluster_id", nullable = false)
    private String clusterId;

    @Column(name = "risk_score", nullable = false)
    private Double riskScore;

    @Column(name = "analysis_algorithm", nullable = false)
    private String analysisAlgorithm;

    @Column(name = "analysis_algorithm_version", nullable = false)
    private String analysisAlgorithmVersion;

    @Column(name = "analysis_request_id", nullable = false)
    private String analysisRequestId;

    @Column(name = "analysis_completed_at", nullable = false)
    private LocalDateTime analysisCompletedAt;

    private MacroAuditEvidence(TicketAudit ticketAudit, MacroAnalysisEvidence evidence) {
        this.ticketAudit = ticketAudit;
        this.evidenceKey = MacroEvidenceKey.create(
                evidence.eventId(), evidence.reservationNo(), evidence.accountId());
        this.eventId = evidence.eventId();
        this.reservationNo = evidence.reservationNo();
        this.accountId = evidence.accountId();
        this.deviceIdHash = evidence.deviceIdHash();
        this.ipHash = evidence.ipHash();
        this.clusterId = evidence.clusterId();
        this.riskScore = evidence.riskScore();
        this.analysisAlgorithm = evidence.algorithm();
        this.analysisAlgorithmVersion = evidence.algorithmVersion();
        this.analysisRequestId = evidence.analysisRequestId();
        this.analysisCompletedAt = evidence.analyzedAt();
    }

    /** 감사 내역에 연결할 매크로 증거를 만듭니다. */
    public static MacroAuditEvidence create(TicketAudit ticketAudit, MacroAnalysisEvidence evidence) {
        return new MacroAuditEvidence(ticketAudit, evidence);
    }
}
