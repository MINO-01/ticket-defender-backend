package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
@Table(name = "tenants")
public class Tenant {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 36)
    private UUID id;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, length = 24)
    private String status;

    public Tenant(UUID id, String name, AccessRecordStatus status) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.status = Objects.requireNonNull(status).name();
    }
}
