package com.ticket.defender_core.config;

import com.ticket.defender_core.application.service.A3SyntheticReservationImportProvisioner;
import com.ticket.defender_core.domain.ReservationImportBatch;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.web.server.context.ConfigurableWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class A3SyntheticReservationImportProvisioningRunnerTest {
    @Test
    void dryRunModeDelegatesOnlyToDryRun() throws Exception {
        A3SyntheticReservationImportProvisioner provisioner = mock(A3SyntheticReservationImportProvisioner.class);
        A3SyntheticReservationImportProvisioningRunner runner = new A3SyntheticReservationImportProvisioningRunner(
                provisioner, mock(ApplicationContext.class));
        ReflectionTestUtils.setField(runner, "enabled", true);
        ReflectionTestUtils.setField(runner, "execute", false);
        ReflectionTestUtils.setField(runner, "tenantId", "tenant-id");
        ReflectionTestUtils.setField(runner, "eventId", "event-id");
        ReflectionTestUtils.setField(runner, "membershipId", "membership-id");
        ReflectionTestUtils.setField(runner, "isolationId", "isolation-id");
        ReflectionTestUtils.setField(runner, "expectedDatabaseName", "td_a3_test");
        ReflectionTestUtils.setField(runner, "fixturePath", "synthetic-fixture.properties");
        A3SyntheticReservationImportProvisioner.Request expected = new A3SyntheticReservationImportProvisioner.Request(
                "tenant-id", "event-id", "membership-id", "isolation-id", "td_a3_test",
                "synthetic-fixture.properties");
        when(provisioner.dryRun(expected)).thenReturn(new A3SyntheticReservationImportProvisioner.Result(
                false, false, 1, ReservationImportBatch.Status.ACCEPTED));

        runner.run(mock(ApplicationArguments.class));

        verify(provisioner).dryRun(expected);
        verify(provisioner, never()).execute(any());
    }

    @Test
    void executeModeIsRejectedWhenStartedAsAWebApplication() {
        A3SyntheticReservationImportProvisioner provisioner = mock(A3SyntheticReservationImportProvisioner.class);
        A3SyntheticReservationImportProvisioningRunner runner = new A3SyntheticReservationImportProvisioningRunner(
                provisioner, mock(ConfigurableWebServerApplicationContext.class));
        ReflectionTestUtils.setField(runner, "enabled", true);
        ReflectionTestUtils.setField(runner, "execute", true);

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("A3_SYNTHETIC_IMPORT_WEB_APPLICATION_FORBIDDEN");
        verifyNoInteractions(provisioner);
    }
}
