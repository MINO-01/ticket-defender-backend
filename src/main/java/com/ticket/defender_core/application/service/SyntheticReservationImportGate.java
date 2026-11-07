package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.SyntheticReservationImportTenantRepository;
import com.ticket.defender_core.domain.SyntheticReservationImportTenant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * 실제 고객사 예매 반입을 활성화하지 않는 합성 격리 게이트입니다.
 * 설정 한 개만으로는 열리지 않으며, 격리 DB와 서버 등록 tenant를 모두 요구합니다.
 */
@Component
public class SyntheticReservationImportGate {
    static final String DISABLED = "RESERVATION_IMPORT_DISABLED";
    static final String NOT_APPROVED = "RESERVATION_IMPORT_OPERATION_NOT_APPROVED";
    static final String ISOLATION_UNVERIFIED = "RESERVATION_IMPORT_ISOLATION_UNVERIFIED";
    static final String SCOPE_UNVERIFIED = "RESERVATION_IMPORT_SYNTHETIC_SCOPE_UNVERIFIED";
    private static final String SYNTHETIC_ISOLATED = "SYNTHETIC_ISOLATED";

    private final boolean enabled;
    private final String operationalMode;
    private final UUID isolationId;
    private final boolean isolatedDatasource;
    private final String hmacMasterKey;
    private final String hmacKeyVersion;
    private final SyntheticReservationImportTenantRepository tenants;

    public SyntheticReservationImportGate(
            @Value("${ticket-defender.track2-candidate-import.enabled:false}") boolean enabled,
            @Value("${ticket-defender.track2-candidate-import.operational-mode:DISABLED}") String operationalMode,
            @Value("${ticket-defender.track2-candidate-import.synthetic-isolation-id:}") String isolationId,
            @Value("${ticket-defender.track2-candidate-import.reference-hmac-master-key:}") String hmacMasterKey,
            @Value("${ticket-defender.track2-candidate-import.reference-hmac-key-version:}") String hmacKeyVersion,
            @Value("${spring.datasource.url:}") String datasourceUrl,
            SyntheticReservationImportTenantRepository tenants) {
        this.enabled = enabled;
        this.operationalMode = operationalMode;
        this.isolationId = parseUuid(isolationId);
        this.isolatedDatasource = OfficialPdfOperationGate.isolatedDatasource(datasourceUrl);
        this.hmacMasterKey = hmacMasterKey;
        this.hmacKeyVersion = hmacKeyVersion;
        this.tenants = tenants;
    }

    public UUID requireSyntheticScope(UUID tenantId, LocalDateTime now) {
        requireProvisioningEnvironment(isolationId);
        SyntheticReservationImportTenant registration = tenants.findByTenantIdForShare(tenantId).orElse(null);
        if (registration == null || !isolationId.equals(registration.getIsolationId())
                || registration.getRegisteredAt().isAfter(now)) throw conflict(SCOPE_UNVERIFIED);
        return isolationId;
    }

    /**
     * Validates only the fail-closed environment signals shared by the one-time A3 provisioner.
     * It intentionally does not accept a tenant scope: the provisioner must create or verify the
     * server-owned registration before {@link #requireSyntheticScope(UUID, LocalDateTime)} can pass.
     */
    UUID requireProvisioningEnvironment(UUID expectedIsolationId) {
        if (!enabled) throw conflict(DISABLED);
        if (!SYNTHETIC_ISOLATED.equals(operationalMode)) throw conflict(NOT_APPROVED);
        if (isolationId == null || expectedIsolationId == null || !isolationId.equals(expectedIsolationId)
                || !isolatedDatasource) throw conflict(ISOLATION_UNVERIFIED);
        if (hmacMasterKey == null || hmacMasterKey.length() < 32 || hmacKeyVersion == null || hmacKeyVersion.isBlank())
            throw conflict(ISOLATION_UNVERIFIED);
        return isolationId;
    }

    public TenantPurposeReferenceHasher referenceHasher() {
        try {
            return new TenantPurposeReferenceHasher(hmacMasterKey, hmacKeyVersion);
        } catch (IllegalArgumentException invalid) {
            throw conflict(ISOLATION_UNVERIFIED);
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            UUID parsed = UUID.fromString(value.trim());
            return parsed.toString().equals(value.trim().toLowerCase(Locale.ROOT)) ? parsed : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static CaseApiException conflict(String code) {
        return new CaseApiException(HttpStatus.CONFLICT, code);
    }
}
