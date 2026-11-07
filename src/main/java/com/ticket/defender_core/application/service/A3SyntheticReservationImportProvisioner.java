package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.CandidatePipelineDto;
import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipRoleRepository;
import com.ticket.defender_core.adapter.out.persistence.SyntheticReservationImportTenantRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.adapter.out.persistence.VerifiedPerformanceRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.MembershipEventGrant;
import com.ticket.defender_core.domain.MembershipRole;
import com.ticket.defender_core.domain.ReservationImportBatch;
import com.ticket.defender_core.domain.SyntheticReservationImportTenant;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import com.ticket.defender_core.domain.VerifiedPerformance;
import com.ticket.defender_core.security.AdminRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Non-web, fail-closed bootstrap for the A3 synthetic reservation import scope. It never
 * receives a request from an HTTP client and delegates the actual snapshot persistence to the
 * same {@link ReservationImportService} used by the protected reservation-imports endpoint.
 */
@Service
public class A3SyntheticReservationImportProvisioner {
    static final String FIXTURE_TYPE = "A3_SYNTHETIC_RESERVATION_SNAPSHOT_V1";
    private static final int MAX_FIXTURE_BYTES = 1_000_000;
    private static final Pattern SYNTHETIC_REFERENCE = Pattern.compile("SYNTHETIC-[A-Z0-9][A-Z0-9_-]{7,120}");

    private final SyntheticReservationImportGate gate;
    private final DataSource dataSource;
    private final TenantRepository tenants;
    private final TenantEventRepository events;
    private final AdminMembershipRepository memberships;
    private final MembershipRoleRepository roles;
    private final MembershipEventGrantRepository grants;
    private final VerifiedPerformanceRepository performances;
    private final SyntheticReservationImportTenantRepository registrations;
    private final ReservationImportService imports;

    public A3SyntheticReservationImportProvisioner(SyntheticReservationImportGate gate, DataSource dataSource,
            TenantRepository tenants, TenantEventRepository events, AdminMembershipRepository memberships,
            MembershipRoleRepository roles, MembershipEventGrantRepository grants,
            VerifiedPerformanceRepository performances, SyntheticReservationImportTenantRepository registrations,
            ReservationImportService imports) {
        this.gate = gate;
        this.dataSource = dataSource;
        this.tenants = tenants;
        this.events = events;
        this.memberships = memberships;
        this.roles = roles;
        this.grants = grants;
        this.performances = performances;
        this.registrations = registrations;
        this.imports = imports;
    }

    @Transactional(readOnly = true)
    public Result dryRun(Request request) {
        return validate(request, false).result(false, false, null);
    }

    @Transactional
    public Result execute(Request request) {
        Validated validated = validate(request, true);
        SyntheticReservationImportTenant current = registrations.findByTenantIdForShare(validated.tenantId()).orElse(null);
        boolean created = false;
        if (current == null) {
            registrations.saveAndFlush(new SyntheticReservationImportTenant(validated.tenantId(),
                    validated.isolationId(), now()));
            created = true;
        } else if (!validated.isolationId().equals(current.getIsolationId())) {
            throw failed("A3_SYNTHETIC_IMPORT_REGISTRATION_CONFLICT");
        }

        CandidatePipelineDto.ImportResult result = imports.importSnapshotForTrustedProvisioning(validated.tenantId(),
                validated.eventId(), validated.membershipId(), deterministicIdempotencyKey(validated), validated.input());
        if (result.status() != ReservationImportBatch.Status.ACCEPTED) {
            throw failed("A3_SYNTHETIC_IMPORT_NOT_ACCEPTED");
        }
        return validated.result(true, created, result.status());
    }

    private Validated validate(Request request, boolean forUpdate) {
        if (request == null) throw failed("A3_SYNTHETIC_IMPORT_CONFIGURATION_INVALID");
        UUID tenantId = uuid(request.tenantId());
        UUID eventId = uuid(request.eventId());
        UUID membershipId = uuid(request.membershipId());
        UUID configuredIsolationId = uuid(request.isolationId());
        String expectedDatabaseName = databaseName(request.expectedDatabaseName());
        gate.requireProvisioningEnvironment(configuredIsolationId);
        verifyMySqlDatabase(expectedDatabaseName);
        SnapshotFixture fixture = readFixture(request.fixturePath());
        CandidatePipelineDto.ImportReservations input = fixture.toInput();
        validateFixture(input);

        Tenant tenant = (forUpdate ? tenants.lockByIdForUpdate(tenantId) : tenants.lockById(tenantId))
                .orElseThrow(() -> failed("A3_SYNTHETIC_IMPORT_TENANT_UNVERIFIED"));
        if (!AccessRecordStatus.ACTIVE.name().equals(tenant.getStatus()))
            throw failed("A3_SYNTHETIC_IMPORT_TENANT_UNVERIFIED");
        TenantEvent event = events.lockById(eventId)
                .orElseThrow(() -> failed("A3_SYNTHETIC_IMPORT_EVENT_UNVERIFIED"));
        if (!tenantId.equals(event.getTenantId()) || !AccessRecordStatus.ACTIVE.name().equals(event.getStatus()))
            throw failed("A3_SYNTHETIC_IMPORT_EVENT_UNVERIFIED");
        AdminMembershipEntity membership = memberships.findById(membershipId)
                .orElseThrow(() -> failed("A3_SYNTHETIC_IMPORT_MEMBERSHIP_UNVERIFIED"));
        if (!tenantId.equals(membership.getTenantId()) || !AccessRecordStatus.ACTIVE.name().equals(membership.getStatus())
                || !hasTenantAdminRole(membershipId) || !hasEventGrant(membershipId, tenantId, eventId))
            throw failed("A3_SYNTHETIC_IMPORT_MEMBERSHIP_UNVERIFIED");
        VerifiedPerformance performance = performances.findById(input.performanceId())
                .orElseThrow(() -> failed("A3_SYNTHETIC_IMPORT_PERFORMANCE_UNVERIFIED"));
        if (!tenantId.equals(performance.getTenantId()) || !eventId.equals(performance.getEventId())
                || performance.getStatus() != VerifiedPerformance.Status.ACTIVE)
            throw failed("A3_SYNTHETIC_IMPORT_PERFORMANCE_UNVERIFIED");
        SyntheticReservationImportTenant registration = registrations.findByTenantIdForShare(tenantId).orElse(null);
        if (registration != null && !configuredIsolationId.equals(registration.getIsolationId()))
            throw failed("A3_SYNTHETIC_IMPORT_REGISTRATION_CONFLICT");
        return new Validated(tenantId, eventId, membershipId, configuredIsolationId, input);
    }

    private boolean hasTenantAdminRole(UUID membershipId) {
        return roles.findAllByMembershipId(membershipId).stream()
                .map(MembershipRole::getRole).anyMatch(AdminRole.TENANT_ADMIN.name()::equals);
    }

    private boolean hasEventGrant(UUID membershipId, UUID tenantId, UUID eventId) {
        return grants.findAllByMembershipIdAndTenantId(membershipId, tenantId).stream()
                .map(MembershipEventGrant::getEventId).anyMatch(eventId::equals);
    }

    private void verifyMySqlDatabase(String expectedDatabaseName) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            if (!"MySQL".equalsIgnoreCase(metadata.getDatabaseProductName())
                    || !expectedDatabaseName.equals(connection.getCatalog()))
                throw failed("A3_SYNTHETIC_IMPORT_DATABASE_UNVERIFIED");
        } catch (A3SyntheticImportProvisioningException failed) {
            throw failed;
        } catch (SQLException unavailable) {
            throw failed("A3_SYNTHETIC_IMPORT_DATABASE_UNVERIFIED");
        }
    }

    private SnapshotFixture readFixture(String fixturePath) {
        try {
            if (fixturePath == null || fixturePath.isBlank()) throw failed("A3_SYNTHETIC_IMPORT_FIXTURE_INVALID");
            Path path = Path.of(fixturePath).normalize();
            Path workspace = Path.of("").toAbsolutePath().normalize();
            if (!path.isAbsolute() || !Files.isRegularFile(path) || Files.size(path) > MAX_FIXTURE_BYTES
                    || path.toRealPath().startsWith(workspace.toRealPath()))
                throw failed("A3_SYNTHETIC_IMPORT_FIXTURE_INVALID");
            Properties values = new Properties();
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                values.load(reader);
            }
            SnapshotFixture fixture = fixture(values);
            if (!FIXTURE_TYPE.equals(fixture.fixtureType()) || !Boolean.TRUE.equals(fixture.completeSnapshot()))
                throw failed("A3_SYNTHETIC_IMPORT_FIXTURE_INVALID");
            return fixture;
        } catch (A3SyntheticImportProvisioningException failed) {
            throw failed;
        } catch (IOException | RuntimeException invalid) {
            throw failed("A3_SYNTHETIC_IMPORT_FIXTURE_INVALID");
        }
    }

    private SnapshotFixture fixture(Properties values) {
        int rowCount = boundedRowCount(values.getProperty("row-count"));
        Set<String> allowed = new HashSet<>(Set.of("fixture-type", "complete-snapshot", "performance-id",
                "source-version", "row-count"));
        List<SnapshotRow> rows = new ArrayList<>(rowCount);
        for (int index = 0; index < rowCount; index++) {
            String prefix = "row." + index + '.';
            allowed.add(prefix + "reservation-reference");
            allowed.add(prefix + "zone");
            allowed.add(prefix + "row");
            allowed.add(prefix + "seat");
            rows.add(new SnapshotRow(values.getProperty(prefix + "reservation-reference"),
                    values.getProperty(prefix + "zone"), values.getProperty(prefix + "row"),
                    values.getProperty(prefix + "seat")));
        }
        if (!values.stringPropertyNames().equals(allowed)) throw failed("A3_SYNTHETIC_IMPORT_FIXTURE_INVALID");
        try {
            return new SnapshotFixture(values.getProperty("fixture-type"),
                    Boolean.valueOf("true".equals(values.getProperty("complete-snapshot"))),
                    UUID.fromString(values.getProperty("performance-id")), values.getProperty("source-version"), rows);
        } catch (IllegalArgumentException invalid) {
            throw failed("A3_SYNTHETIC_IMPORT_FIXTURE_INVALID");
        }
    }

    private static int boundedRowCount(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,3}"))
            throw failed("A3_SYNTHETIC_IMPORT_FIXTURE_INVALID");
        int rowCount = Integer.parseInt(value);
        if (rowCount > 1_000) throw failed("A3_SYNTHETIC_IMPORT_FIXTURE_INVALID");
        return rowCount;
    }

    private void validateFixture(CandidatePipelineDto.ImportReservations input) {
        try {
            CandidatePipelineSupport.bounded(input.sourceVersion(), 128);
            List<CandidatePipelineDto.ReservationRow> rows = input.rows();
            if (!Boolean.TRUE.equals(input.completeSnapshot()) || rows == null || rows.isEmpty() || rows.size() > 1_000)
                throw failed("A3_SYNTHETIC_IMPORT_FIXTURE_INVALID");
            Set<String> references = new HashSet<>();
            for (CandidatePipelineDto.ReservationRow row : rows) {
                if (row == null || row.reservationReference() == null
                        || !SYNTHETIC_REFERENCE.matcher(row.reservationReference()).matches()
                        || !references.add(row.reservationReference()))
                    throw failed("A3_SYNTHETIC_IMPORT_FIXTURE_INVALID");
                CandidatePipelineSupport.seat(row.zone());
                CandidatePipelineSupport.seat(row.row());
                CandidatePipelineSupport.seat(row.seat());
            }
        } catch (A3SyntheticImportProvisioningException failed) {
            throw failed;
        } catch (RuntimeException invalid) {
            throw failed("A3_SYNTHETIC_IMPORT_FIXTURE_INVALID");
        }
    }

    private static UUID uuid(String value) {
        try {
            UUID parsed = UUID.fromString(value == null ? "" : value.trim());
            if (!parsed.toString().equals(value.trim().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("non-canonical UUID");
            return parsed;
        } catch (IllegalArgumentException invalid) {
            throw failed("A3_SYNTHETIC_IMPORT_CONFIGURATION_INVALID");
        }
    }

    private static String databaseName(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{1,64}"))
            throw failed("A3_SYNTHETIC_IMPORT_CONFIGURATION_INVALID");
        return value;
    }

    private static String deterministicIdempotencyKey(Validated validated) {
        String value = "A3_SYNTHETIC_RESERVATION_IMPORT|" + validated.tenantId() + '|' + validated.eventId()
                + '|' + validated.input().performanceId() + '|' + validated.input().sourceVersion().strip();
        return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static A3SyntheticImportProvisioningException failed(String code) {
        return new A3SyntheticImportProvisioningException(code);
    }

    public record Request(String tenantId, String eventId, String membershipId, String isolationId,
            String expectedDatabaseName, String fixturePath) { }

    public record Result(boolean executed, boolean registrationCreated, int fixtureRowCount,
            ReservationImportBatch.Status importStatus) { }

    private record Validated(UUID tenantId, UUID eventId, UUID membershipId, UUID isolationId,
            CandidatePipelineDto.ImportReservations input) {
        Result result(boolean executed, boolean registrationCreated, ReservationImportBatch.Status importStatus) {
            return new Result(executed, registrationCreated, input.rows().size(), importStatus);
        }
    }

    public static final class A3SyntheticImportProvisioningException extends RuntimeException {
        private final String code;

        A3SyntheticImportProvisioningException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public record SnapshotFixture(String fixtureType, Boolean completeSnapshot, UUID performanceId,
            String sourceVersion, List<SnapshotRow> rows) {
        CandidatePipelineDto.ImportReservations toInput() {
            return new CandidatePipelineDto.ImportReservations(performanceId, sourceVersion, completeSnapshot,
                    rows == null ? null : rows.stream().map(row -> row == null ? null : row.toInput()).toList());
        }
    }

    public record SnapshotRow(String reservationReference, String zone, String row, String seat) {
        CandidatePipelineDto.ReservationRow toInput() {
            return new CandidatePipelineDto.ReservationRow(reservationReference, zone, row, seat);
        }
    }
}
