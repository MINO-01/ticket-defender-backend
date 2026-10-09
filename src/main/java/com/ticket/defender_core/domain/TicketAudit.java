package com.ticket.defender_core.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import com.ticket.defender_core.global.converter.StringListConverter;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "ticket_audit",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_reservation_reporter", columnNames = {"reservation_no", "reporter_id"})
        },
        indexes = {
                @Index(name = "idx_ticket_audit_reservation_evidence_status", columnList = "reservation_no,evidence_type,status")
        }
)
public class TicketAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String accountId;
    private String paymentHash;
    private String addressHash;

    private String reservationNo;
    private Double mappingScore;

    private String reporterId;

    @Enumerated(EnumType.STRING)
    private AuditStatus status;

    @Enumerated(EnumType.STRING)
    private EvidenceType evidenceType;

    private LocalDateTime detectedAt;

    /** 제보 접수 API가 기록한 서버 시각. 과거 데이터는 detectedAt을 기준으로 처리합니다. */
    @Column(name = "report_received_at")
    private LocalDateTime reportReceivedAt;

    @Convert(converter = StringListConverter.class)
    @Column(name = "evidence_image_urls", columnDefinition = "TEXT")
    private List<String> evidenceImageUrls;

    @OneToOne(mappedBy = "ticketAudit", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private MacroAuditEvidence macroAuditEvidence;

    /** 기본 감사 내역을 만듭니다. */
    public TicketAudit(String accountId, String paymentHash, String addressHash) {
        this.accountId = accountId;
        this.paymentHash = paymentHash;
        this.addressHash = addressHash;
        this.status = AuditStatus.NORMAL;
    }

    /** 상태를 적발 완료로 바꾸고 적발 시각을 기록합니다. */
    public void markAsFraud() {
        this.status = AuditStatus.FRAUD_DETECTED;
        if (this.evidenceType == null) {
            this.evidenceType = EvidenceType.MACRO_GRAPH;
        }
        this.detectedAt = LocalDateTime.now();
    }

    /** 감사 정보와 매크로 증거를 함께 구성합니다. */
    public static TicketAudit createMacroAudit(MacroAnalysisEvidence evidence) {
        TicketAudit audit = new TicketAudit(
                evidence.accountId(),
                evidence.paymentHash(),
                evidence.addressHash()
        );
        audit.reservationNo = evidence.reservationNo();
        audit.evidenceType = EvidenceType.MACRO_GRAPH;
        audit.status = AuditStatus.FRAUD_DETECTED;
        audit.detectedAt = LocalDateTime.now();
        audit.macroAuditEvidence = MacroAuditEvidence.create(audit, evidence);
        return audit;
    }

    /** 접수 시각과 캡처 정보를 담은 팬 제보를 만듭니다. */
    public static TicketAudit createVlmAudit(
            String reservationNo,
            String accountId,
            Double mappingScore,
            String reporterId,
            List<String> evidenceImageUrls,
            LocalDateTime reportReceivedAt
    ) {
        Objects.requireNonNull(reportReceivedAt, "제보 접수 시각은 필수입니다.");

        TicketAudit audit = new TicketAudit();
        audit.reservationNo = reservationNo;
        audit.accountId = accountId;
        audit.mappingScore = mappingScore;
        audit.reporterId = reporterId;
        audit.evidenceImageUrls = evidenceImageUrls;
        audit.evidenceType = EvidenceType.FAN_REPORT;
        audit.status = AuditStatus.FRAUD_DETECTED;
        audit.detectedAt = LocalDateTime.now();
        audit.reportReceivedAt = reportReceivedAt;
        return audit;
    }
}
