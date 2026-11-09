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

/** 실행별 Louvain 군집 요약입니다. riskScore는 조사 우선순위이며 불법 확률이 아닙니다. */
@Entity @Immutable @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "track1_purchase_limit_run_clusters", uniqueConstraints =
        @UniqueConstraint(name = "uk_t1_limit_run_cluster", columnNames = {"run_id", "cluster_id"}))
public class Track1PurchaseLimitRunCluster {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "run_id", nullable = false, length = 36) private UUID runId;
    @Column(name = "cluster_id", nullable = false, length = 128) private String clusterId;
    @Column(name = "risk_score", nullable = false) private double riskScore;
    @Column(name = "member_count", nullable = false) private int memberCount;

    public Track1PurchaseLimitRunCluster(UUID runId, String clusterId, double riskScore, int memberCount) {
        id = UUID.randomUUID();
        this.runId = Objects.requireNonNull(runId);
        this.clusterId = Objects.requireNonNull(clusterId);
        this.riskScore = riskScore;
        this.memberCount = memberCount;
    }
}
