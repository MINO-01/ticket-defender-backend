package com.ticket.defender_core.domain;

import com.ticket.defender_core.security.AdminRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "membership_roles",
        uniqueConstraints = @UniqueConstraint(name = "uk_membership_roles_member_role", columnNames = {"membership_id", "role"}))
public class MembershipRole {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "membership_id", nullable = false, length = 36)
    private UUID membershipId;

    @Column(nullable = false, length = 24)
    private String role;

    public MembershipRole(UUID id, UUID membershipId, AdminRole role) {
        this.id = Objects.requireNonNull(id);
        this.membershipId = Objects.requireNonNull(membershipId);
        this.role = Objects.requireNonNull(role).name();
    }
}
