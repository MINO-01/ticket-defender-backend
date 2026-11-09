package com.ticket.defender_core.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;
import java.util.UUID;

/** 구매 한도 초과 조사 신호입니다. 부정 거래 확정이나 Case 승인 근거가 아닙니다. */
@Entity @Immutable @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "track1_purchase_limit_findings", uniqueConstraints =
        @UniqueConstraint(name = "uk_t1_limit_finding_run_account", columnNames = {"run_id", "account_ref_hmac"}))
public class Track1PurchaseLimitFinding {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "run_id", nullable = false, length = 36) private UUID runId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "account_ref_hmac", nullable = false, length = 64) private String accountRefHmac;
    @Column(name = "effective_ticket_quantity", nullable = false) private int effectiveTicketQuantity;
    @Column(name = "max_tickets_per_account", nullable = false) private int maxTicketsPerAccount;
    @Column(name = "policy_version", nullable = false, length = 128) private String policyVersion;

    public Track1PurchaseLimitFinding(UUID run, String account, int quantity, int maximum, String version) {
        id = UUID.randomUUID();
        runId = Objects.requireNonNull(run);
        accountRefHmac = Objects.requireNonNull(account);
        if (quantity < 1 || maximum < 1) throw new IllegalArgumentException("invalid finding");
        effectiveTicketQuantity = quantity;
        maxTicketsPerAccount = maximum;
        policyVersion = Objects.requireNonNull(version);
    }
}
