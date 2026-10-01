package com.ticket.defender_core.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;
import com.ticket.defender_core.global.converter.StringListConverter;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "ticket_audit",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_account", columnNames = {"payment_hash", "account_id"}),
                @UniqueConstraint(name = "uk_reservation_reporter", columnNames = {"reservation_no", "reporter_id"})
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

    @Convert(converter = StringListConverter.class)
    @Column(name = "evidence_image_urls", columnDefinition = "TEXT")
    private List<String> evidenceImageUrls;

    public TicketAudit(String accountId, String paymentHash, String addressHash) {
        this.accountId = accountId;
        this.paymentHash = paymentHash;
        this.addressHash = addressHash;
        this.status = AuditStatus.NORMAL;
    }

    public void markAsFraud() {
        this.status = AuditStatus.FRAUD_DETECTED;
        if (this.evidenceType == null) {
            this.evidenceType = EvidenceType.MACRO_GRAPH;
        }
        this.detectedAt = LocalDateTime.now();
    }

    public static TicketAudit createMacroAudit(String accountId, String paymentHash, String addressHash) {
        TicketAudit audit = new TicketAudit(accountId, paymentHash, addressHash);
        audit.evidenceType = EvidenceType.MACRO_GRAPH;
        audit.status = AuditStatus.FRAUD_DETECTED;
        audit.detectedAt = LocalDateTime.now();
        return audit;
    }

    public static TicketAudit createVlmAudit(String reservationNo, String accountId, Double mappingScore, String reporterId, List<String> evidenceImageUrls) {
        TicketAudit audit = new TicketAudit();
        audit.reservationNo = reservationNo;
        audit.accountId = accountId;
        audit.mappingScore = mappingScore;
        audit.reporterId = reporterId;
        audit.evidenceImageUrls = evidenceImageUrls;
        audit.evidenceType = EvidenceType.FAN_REPORT;
        audit.status = AuditStatus.FRAUD_DETECTED;
        audit.detectedAt = LocalDateTime.now();
        return audit;
    }
}