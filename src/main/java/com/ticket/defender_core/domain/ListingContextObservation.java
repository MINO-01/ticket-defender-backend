package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Listing Context 응답의 필드 관찰 1건입니다. 계약의 state, value, evidenceQuote, unknownReason, candidates를 그대로 보존합니다. */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "listing_context_observations", uniqueConstraints =
        @UniqueConstraint(name = "uk_listing_observations_field", columnNames = {"analysis_id", "field_name"}))
public class ListingContextObservation {
    public enum Field { eventName, performanceAt, platformDisplayName, askingPrice, currency, saleText, zone, row, seat }
    public enum State { FOUND, UNKNOWN, AMBIGUOUS }
    public enum UnknownReason { ABSENT, UNREADABLE, NOT_CONFIRMED }

    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "id", length = 36, nullable = false)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "analysis_id", length = 36, nullable = false)
    private UUID analysisId;
    @Enumerated(EnumType.STRING) @Column(name = "field_name", length = 32, nullable = false)
    private Field fieldName;
    @Enumerated(EnumType.STRING) @Column(name = "state", length = 16, nullable = false)
    private State state;
    @Column(name = "value_text", length = 256)
    private String valueText;
    @Column(name = "price_raw", length = 64)
    private String priceRaw;
    @Column(name = "price_amount", length = 15)
    private String priceAmount;
    @Column(name = "evidence_quote", length = 512)
    private String evidenceQuote;
    @Enumerated(EnumType.STRING) @Column(name = "unknown_reason", length = 16)
    private UnknownReason unknownReason;
    @Column(name = "candidate_1", length = 128)
    private String candidate1;
    @Column(name = "candidate_2", length = 128)
    private String candidate2;
    @Column(name = "candidate_3", length = 128)
    private String candidate3;

    public ListingContextObservation(UUID analysisId, Field fieldName, State state, String valueText,
            String priceRaw, String priceAmount, String evidenceQuote, UnknownReason unknownReason,
            List<String> candidates) {
        this.id = UUID.randomUUID();
        this.analysisId = Objects.requireNonNull(analysisId);
        this.fieldName = Objects.requireNonNull(fieldName);
        this.state = Objects.requireNonNull(state);
        this.valueText = valueText;
        this.priceRaw = priceRaw;
        this.priceAmount = priceAmount;
        this.evidenceQuote = evidenceQuote;
        this.unknownReason = unknownReason;
        if (candidates.size() > 3) throw new IllegalArgumentException("Too many candidates");
        this.candidate1 = candidates.size() > 0 ? candidates.get(0) : null;
        this.candidate2 = candidates.size() > 1 ? candidates.get(1) : null;
        this.candidate3 = candidates.size() > 2 ? candidates.get(2) : null;
    }

    public List<String> candidates() {
        return java.util.stream.Stream.of(candidate1, candidate2, candidate3).filter(Objects::nonNull).toList();
    }
}
