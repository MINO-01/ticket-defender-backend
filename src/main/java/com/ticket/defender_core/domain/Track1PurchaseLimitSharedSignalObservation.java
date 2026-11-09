package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;
import java.util.UUID;

/** Persisted, aggregate-only Data v2 observation bound to one Track 1 analysis run. */
@Entity @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "track1_purchase_limit_shared_signal_observations", indexes =
        @Index(name = "idx_t1_signal_observation_run", columnList = "run_id"), uniqueConstraints =
        @UniqueConstraint(name = "uk_t1_signal_observation_run_id", columnNames = {"run_id", "observation_id"}))
public class Track1PurchaseLimitSharedSignalObservation {
    public enum SignalType { IP, ADDRESS }
    public enum TicketQuantityStatus { VERIFIED, UNAVAILABLE }

    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "run_id", nullable = false, length = 36) private UUID runId;
    @Column(name = "observation_id", nullable = false, length = 128) private String observationId;
    @Column(name = "scope_type", nullable = false, length = 64) private String scopeType;
    @Column(name = "scope_id", nullable = false, length = 128) private String scopeId;
    @Enumerated(EnumType.STRING) @Column(name = "signal_type", nullable = false, length = 16) private SignalType signalType;
    @Column(name = "distinct_account_count", nullable = false) private int distinctAccountCount;
    @Column(name = "scope_account_count", nullable = false) private int scopeAccountCount;
    @Column(name = "distinct_reservation_count", nullable = false) private int distinctReservationCount;
    @Column(name = "total_ticket_quantity") private Integer totalTicketQuantity;
    @Enumerated(EnumType.STRING) @Column(name = "ticket_quantity_status", nullable = false, length = 16) private TicketQuantityStatus ticketQuantityStatus;
    @Column(name = "payment_shared_account_count", nullable = false) private int paymentSharedAccountCount;
    @Column(name = "device_shared_account_count", nullable = false) private int deviceSharedAccountCount;
    @Column(name = "ip_network_type", length = 64) private String ipNetworkType;
    @Column(name = "purchase_window_seconds") private Integer purchaseWindowSeconds;
    @Column(name = "excluded_from_louvain", nullable = false) private boolean excludedFromLouvain;

    public Track1PurchaseLimitSharedSignalObservation(UUID runId, String observationId, String scopeType, String scopeId,
            SignalType signalType, int distinctAccountCount, int scopeAccountCount, int distinctReservationCount,
            Integer totalTicketQuantity, TicketQuantityStatus ticketQuantityStatus, int paymentSharedAccountCount,
            int deviceSharedAccountCount, String ipNetworkType, Integer purchaseWindowSeconds, boolean excludedFromLouvain) {
        id = UUID.randomUUID(); this.runId = Objects.requireNonNull(runId);
        this.observationId = bounded(observationId, 128); this.scopeType = bounded(scopeType, 64); this.scopeId = bounded(scopeId, 128);
        this.signalType = Objects.requireNonNull(signalType); this.ticketQuantityStatus = Objects.requireNonNull(ticketQuantityStatus);
        if (distinctAccountCount < 0 || scopeAccountCount < 0 || distinctReservationCount < 0
                || paymentSharedAccountCount < 0 || deviceSharedAccountCount < 0 || purchaseWindowSeconds != null && purchaseWindowSeconds < 0
                || totalTicketQuantity != null && totalTicketQuantity < 0
                || ticketQuantityStatus == TicketQuantityStatus.VERIFIED && totalTicketQuantity == null
                || ticketQuantityStatus == TicketQuantityStatus.UNAVAILABLE && totalTicketQuantity != null) throw new IllegalArgumentException("invalid shared signal observation");
        this.distinctAccountCount = distinctAccountCount; this.scopeAccountCount = scopeAccountCount;
        this.distinctReservationCount = distinctReservationCount; this.totalTicketQuantity = totalTicketQuantity;
        this.paymentSharedAccountCount = paymentSharedAccountCount; this.deviceSharedAccountCount = deviceSharedAccountCount;
        this.ipNetworkType = ipNetworkType; this.purchaseWindowSeconds = purchaseWindowSeconds;
        this.excludedFromLouvain = excludedFromLouvain;
    }
    private static String bounded(String value, int max) { if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("invalid observation field"); return value; }
}
