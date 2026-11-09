package com.ticket.defender_core.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** 고객사가 제공한 티켓 또는 주문 라인 1건입니다. 수량은 행 수에서 추정하지 않고 원문 참조는 HMAC 토큰으로만 저장합니다. */
@Entity @Immutable @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "track1_purchase_ledger_rows", uniqueConstraints = {
        @UniqueConstraint(name = "uk_t1_ledger_row_ticket", columnNames = {"batch_id", "ticket_ref_hmac"}),
        @UniqueConstraint(name = "uk_t1_ledger_row_index", columnNames = {"batch_id", "source_row_index"})
}, indexes = @Index(name = "idx_t1_ledger_row_batch_account", columnList = "batch_id,account_ref_hmac"))
public class Track1PurchaseLedgerRow {
    public enum TicketStatus { ACTIVE, CANCELLED, REFUNDED, UNKNOWN }
    public enum QuantityUnit { TICKET, ORDER_LINE }

    /** 관계 신호 토큰 묶음입니다. 하나 이상 있어야 Louvain 입력이 됩니다. */
    public record Signals(String payment, String address, String device, String ip) {
        public boolean any() { return payment != null || address != null || device != null || ip != null; }
    }

    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "batch_id", nullable = false, length = 36) private UUID batchId;
    @Column(name = "source_row_index", nullable = false) private int sourceRowIndex;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "account_ref_hmac", nullable = false, length = 64) private String accountRefHmac;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "reservation_ref_hmac", nullable = false, length = 64) private String reservationRefHmac;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "ticket_ref_hmac", nullable = false, length = 64) private String ticketRefHmac;
    @Column(name = "purchased_at", nullable = false) private LocalDateTime purchasedAt;
    @Column(name = "ticket_quantity", nullable = false) private int ticketQuantity;
    @Enumerated(EnumType.STRING) @Column(name = "quantity_unit", nullable = false, length = 16) private QuantityUnit quantityUnit;
    @Enumerated(EnumType.STRING) @Column(name = "ticket_status", nullable = false, length = 16) private TicketStatus ticketStatus;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "payment_hmac", length = 64) private String paymentHmac;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "address_hmac", length = 64) private String addressHmac;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "device_hmac", length = 64) private String deviceHmac;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "ip_hmac", length = 64) private String ipHmac;
    @Column(name = "source_version", nullable = false, length = 128) private String sourceVersion;
    @Column(name = "source_as_of", nullable = false) private LocalDateTime sourceAsOf;

    public Track1PurchaseLedgerRow(UUID batchId, int index, String account, String reservation, String ticket,
            LocalDateTime purchasedAt, int quantity, QuantityUnit unit, TicketStatus status, Signals signals,
            String sourceVersion, LocalDateTime sourceAsOf) {
        id = UUID.randomUUID();
        this.batchId = Objects.requireNonNull(batchId);
        if (index < 0 || quantity < 1 || (unit == QuantityUnit.TICKET && quantity != 1))
            throw new IllegalArgumentException("invalid ticket row");
        if (signals == null || !signals.any()) throw new IllegalArgumentException("relationship signal required");
        sourceRowIndex = index;
        accountRefHmac = required(account, 64);
        reservationRefHmac = required(reservation, 64);
        ticketRefHmac = required(ticket, 64);
        this.purchasedAt = Objects.requireNonNull(purchasedAt);
        ticketQuantity = quantity;
        quantityUnit = Objects.requireNonNull(unit);
        ticketStatus = Objects.requireNonNull(status);
        paymentHmac = signals.payment();
        addressHmac = signals.address();
        deviceHmac = signals.device();
        ipHmac = signals.ip();
        this.sourceVersion = required(sourceVersion, 128);
        this.sourceAsOf = Objects.requireNonNull(sourceAsOf);
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("invalid ledger value");
        return value;
    }
}
