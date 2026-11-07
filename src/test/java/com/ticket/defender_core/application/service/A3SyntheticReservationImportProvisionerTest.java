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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A3SyntheticReservationImportProvisionerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID EVENT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MEMBERSHIP = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ISOLATION = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID PERFORMANCE = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String DATABASE = "td_a3_test";

    @TempDir Path temp;

    private SyntheticReservationImportGate gate;
    private TenantRepository tenants;
    private TenantEventRepository events;
    private AdminMembershipRepository memberships;
    private MembershipRoleRepository roles;
    private MembershipEventGrantRepository grants;
    private VerifiedPerformanceRepository performances;
    private SyntheticReservationImportTenantRepository registrations;
    private ReservationImportService imports;
    private A3SyntheticReservationImportProvisioner provisioner;
    private Connection connection;

    @BeforeEach
    void setup() throws Exception {
        gate = mock(SyntheticReservationImportGate.class);
        DataSource dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(connection.getCatalog()).thenReturn(DATABASE);
        tenants = mock(TenantRepository.class);
        events = mock(TenantEventRepository.class);
        memberships = mock(AdminMembershipRepository.class);
        roles = mock(MembershipRoleRepository.class);
        grants = mock(MembershipEventGrantRepository.class);
        performances = mock(VerifiedPerformanceRepository.class);
        registrations = mock(SyntheticReservationImportTenantRepository.class);
        imports = mock(ReservationImportService.class);
        provisioner = new A3SyntheticReservationImportProvisioner(gate, dataSource, tenants, events, memberships,
                roles, grants, performances, registrations, imports);
        when(gate.requireProvisioningEnvironment(ISOLATION)).thenReturn(ISOLATION);
        when(tenants.lockById(TENANT)).thenReturn(Optional.of(new Tenant(TENANT, "Synthetic A3 tenant",
                AccessRecordStatus.ACTIVE)));
        when(tenants.lockByIdForUpdate(TENANT)).thenReturn(Optional.of(new Tenant(TENANT, "Synthetic A3 tenant",
                AccessRecordStatus.ACTIVE)));
        when(events.lockById(EVENT)).thenReturn(Optional.of(new TenantEvent(EVENT, TENANT, "a3-event",
                "Synthetic A3 event", AccessRecordStatus.ACTIVE)));
        when(memberships.findById(MEMBERSHIP)).thenReturn(Optional.of(new AdminMembershipEntity(MEMBERSHIP, TENANT,
                "a3-provisioner", AccessRecordStatus.ACTIVE)));
        when(roles.findAllByMembershipId(MEMBERSHIP)).thenReturn(List.of(new MembershipRole(UUID.randomUUID(), MEMBERSHIP,
                AdminRole.TENANT_ADMIN)));
        when(grants.findAllByMembershipIdAndTenantId(MEMBERSHIP, TENANT)).thenReturn(List.of(
                new MembershipEventGrant(UUID.randomUUID(), MEMBERSHIP, TENANT, EVENT)));
        when(performances.findById(PERFORMANCE)).thenReturn(Optional.of(new VerifiedPerformance(PERFORMANCE, TENANT, EVENT,
                "a3-performance", "Synthetic A3 performance", "v1", MEMBERSHIP, LocalDateTime.now())));
        when(registrations.findByTenantIdForShare(TENANT)).thenReturn(Optional.empty());
    }

    @Test
    void dryRunValidatesTheA3ScopeWithoutWritingRegistrationOrImport() throws Exception {
        A3SyntheticReservationImportProvisioner.Result result = provisioner.dryRun(request(writeFixture(
                "SYNTHETIC-A3-RESERVATION-0001", "VIP", "10", "5")));

        assertThat(result.executed()).isFalse();
        assertThat(result.registrationCreated()).isFalse();
        assertThat(result.fixtureRowCount()).isEqualTo(1);
        assertThat(result.importStatus()).isNull();
        verify(registrations, never()).saveAndFlush(any());
        verify(imports, never()).importSnapshotForTrustedProvisioning(any(), any(), any(), any(), any());
    }

    @Test
    void executeRegistersOnceAndDelegatesToTheExistingImportService() throws Exception {
        when(registrations.findByTenantIdForShare(TENANT)).thenReturn(Optional.empty());
        CandidatePipelineDto.ImportResult accepted = new CandidatePipelineDto.ImportResult(UUID.randomUUID(), EVENT,
                PERFORMANCE, "a3-fixture-v1", ReservationImportBatch.Status.ACCEPTED, true, 1, 1, 0, List.of(),
                LocalDateTime.now());
        when(imports.importSnapshotForTrustedProvisioning(eq(TENANT), eq(EVENT), eq(MEMBERSHIP), any(), any()))
                .thenReturn(accepted);

        A3SyntheticReservationImportProvisioner.Result result = provisioner.execute(request(writeFixture(
                "SYNTHETIC-A3-RESERVATION-0001", "VIP", "10", "5")));

        assertThat(result.executed()).isTrue();
        assertThat(result.registrationCreated()).isTrue();
        assertThat(result.importStatus()).isEqualTo(ReservationImportBatch.Status.ACCEPTED);
        verify(registrations).saveAndFlush(any(SyntheticReservationImportTenant.class));
        verify(imports).importSnapshotForTrustedProvisioning(eq(TENANT), eq(EVENT), eq(MEMBERSHIP), any(), any());
    }

    @Test
    void matchingExistingRegistrationIsReusedWithoutCreatingAnotherRow() throws Exception {
        when(registrations.findByTenantIdForShare(TENANT)).thenReturn(Optional.of(
                new SyntheticReservationImportTenant(TENANT, ISOLATION, LocalDateTime.now().minusMinutes(1))));
        CandidatePipelineDto.ImportResult accepted = new CandidatePipelineDto.ImportResult(UUID.randomUUID(), EVENT,
                PERFORMANCE, "a3-fixture-v1", ReservationImportBatch.Status.ACCEPTED, true, 1, 1, 0, List.of(),
                LocalDateTime.now());
        when(imports.importSnapshotForTrustedProvisioning(eq(TENANT), eq(EVENT), eq(MEMBERSHIP), any(), any()))
                .thenReturn(accepted);

        A3SyntheticReservationImportProvisioner.Result result = provisioner.execute(request(writeFixture(
                "SYNTHETIC-A3-RESERVATION-0001", "VIP", "10", "5")));

        assertThat(result.registrationCreated()).isFalse();
        verify(registrations, never()).saveAndFlush(any());
        verify(imports).importSnapshotForTrustedProvisioning(eq(TENANT), eq(EVENT), eq(MEMBERSHIP), any(), any());
    }

    @Test
    void existingDifferentIsolationStopsBeforeImportAndKeepsRegistration() throws Exception {
        when(registrations.findByTenantIdForShare(TENANT)).thenReturn(Optional.of(
                new SyntheticReservationImportTenant(TENANT, UUID.randomUUID(), LocalDateTime.now().minusMinutes(1))));

        assertThatThrownBy(() -> provisioner.execute(request(writeFixture(
                "SYNTHETIC-A3-RESERVATION-0001", "VIP", "10", "5"))))
                .isInstanceOfSatisfying(A3SyntheticReservationImportProvisioner.A3SyntheticImportProvisioningException.class,
                        error -> assertThat(error.code()).isEqualTo("A3_SYNTHETIC_IMPORT_REGISTRATION_CONFLICT"));
        verify(registrations, never()).saveAndFlush(any());
        verify(imports, never()).importSnapshotForTrustedProvisioning(any(), any(), any(), any(), any());
    }

    @Test
    void malformedIsolationAndNonSyntheticReferenceAreRejectedWithoutLeakingFixtureContents() throws Exception {
        Path fixture = writeFixture("ACTUAL-RESERVATION-1234", "VIP", "10", "5");
        A3SyntheticReservationImportProvisioner.Request malformedIsolation = new A3SyntheticReservationImportProvisioner.Request(
                TENANT.toString(), EVENT.toString(), MEMBERSHIP.toString(), "not-a-uuid", DATABASE, fixture.toString());

        assertThatThrownBy(() -> provisioner.dryRun(malformedIsolation))
                .isInstanceOfSatisfying(A3SyntheticReservationImportProvisioner.A3SyntheticImportProvisioningException.class,
                        error -> assertThat(error.code()).isEqualTo("A3_SYNTHETIC_IMPORT_CONFIGURATION_INVALID"));
        assertThatThrownBy(() -> provisioner.dryRun(request(fixture)))
                .isInstanceOfSatisfying(A3SyntheticReservationImportProvisioner.A3SyntheticImportProvisioningException.class,
                        error -> assertThat(error.code()).doesNotContain("ACTUAL-RESERVATION-1234"));
        verify(registrations, never()).saveAndFlush(any());
        verify(imports, never()).importSnapshotForTrustedProvisioning(any(), any(), any(), any(), any());
    }

    @Test
    void differentDatabaseOrTenantScopeIsRejectedBeforeAnyRegistrationWrite() throws Exception {
        when(connection.getCatalog()).thenReturn("other_schema");

        assertThatThrownBy(() -> provisioner.dryRun(request(writeFixture(
                "SYNTHETIC-A3-RESERVATION-0001", "VIP", "10", "5"))))
                .isInstanceOfSatisfying(A3SyntheticReservationImportProvisioner.A3SyntheticImportProvisioningException.class,
                        error -> assertThat(error.code()).isEqualTo("A3_SYNTHETIC_IMPORT_DATABASE_UNVERIFIED"));
        when(connection.getCatalog()).thenReturn(DATABASE);
        when(tenants.lockById(TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provisioner.dryRun(request(writeFixture(
                "SYNTHETIC-A3-RESERVATION-0001", "VIP", "10", "5"))))
                .isInstanceOfSatisfying(A3SyntheticReservationImportProvisioner.A3SyntheticImportProvisioningException.class,
                        error -> assertThat(error.code()).isEqualTo("A3_SYNTHETIC_IMPORT_TENANT_UNVERIFIED"));
        verify(registrations, never()).saveAndFlush(any());
        verify(imports, never()).importSnapshotForTrustedProvisioning(any(), any(), any(), any(), any());
    }

    private A3SyntheticReservationImportProvisioner.Request request(Path fixture) {
        return new A3SyntheticReservationImportProvisioner.Request(TENANT.toString(), EVENT.toString(),
                MEMBERSHIP.toString(), ISOLATION.toString(), DATABASE, fixture.toString());
    }

    private Path writeFixture(String reference, String zone, String row, String seat) throws Exception {
        Path fixture = temp.resolve("snapshot.properties");
        Files.writeString(fixture, """
                fixture-type=A3_SYNTHETIC_RESERVATION_SNAPSHOT_V1
                complete-snapshot=true
                performance-id=%s
                source-version=a3-fixture-v1
                row-count=1
                row.0.reservation-reference=%s
                row.0.zone=%s
                row.0.row=%s
                row.0.seat=%s
                """.formatted(PERFORMANCE, reference, zone, row, seat));
        return fixture;
    }
}
