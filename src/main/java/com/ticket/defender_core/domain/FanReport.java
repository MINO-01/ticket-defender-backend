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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "fan_reports", uniqueConstraints = {
        @UniqueConstraint(name = "uk_fan_reports_receipt", columnNames = "receipt_number"),
        @UniqueConstraint(name = "uk_fan_reports_idempotency", columnNames = "idempotency_key_digest")
}, indexes = @Index(name = "idx_fan_reports_scope_received", columnList = "tenant_id,event_id,received_at"))
public class FanReport {
    public enum ReportType { PREMIUM_RESALE, AUTOMATION_OFFER, FAKE_TICKET, OTHER }
    public enum IntakeStatus { PENDING_STORAGE, RECEIVED, STORAGE_FAILED, ANALYSIS_PENDING, ANALYSIS_FAILED, UNVERIFIABLE }

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36, nullable = false)
    private UUID fanReportId;

    @Column(name = "receipt_number", length = 40, nullable = false)
    private String receiptNumber;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", length = 36, nullable = false)
    private UUID tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", length = 36, nullable = false)
    private UUID eventId;

    @Column(name = "submitted_performance_at", nullable = false)
    private LocalDateTime submittedPerformanceAt;

    @Column(name = "verified_performance_ref", length = 128)
    private String verifiedPerformanceRef;

    @Column(name = "seat_area", length = 160)
    private String seatArea;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", length = 32, nullable = false)
    private ReportType reportType;

    @Column(name = "description", columnDefinition = "text", nullable = false)
    private String description;

    @Column(name = "listing_url", length = 2048)
    private String listingUrl;

    @Column(name = "platform", length = 160)
    private String platform;

    @Column(name = "seller", length = 160)
    private String seller;

    @Column(name = "asking_price", precision = 14, scale = 2)
    private BigDecimal askingPrice;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "retention_until", nullable = false)
    private LocalDateTime retentionUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "intake_status", length = 32, nullable = false)
    private IntakeStatus intakeStatus;

    @Column(name = "idempotency_key_digest", length = 64, nullable = false)
    private String idempotencyKeyDigest;

    @Column(name = "request_digest", length = 64, nullable = false)
    private String requestDigest;

    @Column(name = "lookup_secret_digest", length = 64, nullable = false)
    private String lookupSecretDigest;

    public FanReport(UUID id, String receiptNumber, UUID tenantId, UUID eventId,
                     LocalDateTime submittedPerformanceAt, String seatArea, ReportType reportType,
                     String description, String listingUrl, String platform, String seller,
                     BigDecimal askingPrice, LocalDateTime receivedAt, String idempotencyKeyDigest,
                     String requestDigest, String lookupSecretDigest) {
        this.fanReportId = Objects.requireNonNull(id);
        this.receiptNumber = Objects.requireNonNull(receiptNumber);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.eventId = Objects.requireNonNull(eventId);
        this.submittedPerformanceAt = Objects.requireNonNull(submittedPerformanceAt);
        this.seatArea = seatArea;
        this.reportType = Objects.requireNonNull(reportType);
        this.description = Objects.requireNonNull(description);
        this.listingUrl = listingUrl;
        this.platform = platform;
        this.seller = seller;
        this.askingPrice = askingPrice;
        this.receivedAt = Objects.requireNonNull(receivedAt);
        this.retentionUntil = receivedAt.plusDays(90);
        this.intakeStatus = IntakeStatus.PENDING_STORAGE;
        this.idempotencyKeyDigest = Objects.requireNonNull(idempotencyKeyDigest);
        this.requestDigest = Objects.requireNonNull(requestDigest);
        this.lookupSecretDigest = Objects.requireNonNull(lookupSecretDigest);
    }

    public void setRetentionUntil(LocalDateTime until) {
        if (until == null || !until.isAfter(receivedAt))
            throw new IllegalArgumentException("Retention deadline must follow receipt time");
        this.retentionUntil = until;
    }

    public void markReceived() {
        if (intakeStatus != IntakeStatus.PENDING_STORAGE) throw new IllegalStateException("Report is not pending storage");
        intakeStatus = IntakeStatus.RECEIVED;
    }

    public void markStorageFailed() {
        if (intakeStatus != IntakeStatus.PENDING_STORAGE && intakeStatus != IntakeStatus.STORAGE_FAILED)
            throw new IllegalStateException("Stored report cannot fail storage");
        intakeStatus = IntakeStatus.STORAGE_FAILED;
    }
}
