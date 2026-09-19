package com.ticket.defender_core.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "ticket_audit",
        uniqueConstraints = {

                @UniqueConstraint(
                        name = "uk_payment_account",
                        columnNames = {"payment_hash", "account_id"}
                )
        }
)
public class TicketAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String accountId;
    private String paymentHash;
    private String addressHash;

    @Enumerated(EnumType.STRING)
    private AuditStatus status;
    private LocalDateTime detectedAt;

    public TicketAudit(String accountId, String paymentHash, String addressHash) {
        this.accountId = accountId;
        this.paymentHash = paymentHash;
        this.addressHash = addressHash;
        this.status = AuditStatus.NORMAL;
    }

    public void markAsFraud() {
        this.status = AuditStatus.FRAUD_DETECTED;
        this.detectedAt = LocalDateTime.now();
    }
}