package com.ticket.defender_core.config;

import com.ticket.defender_core.application.service.A3SyntheticReservationImportProvisioner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.web.server.context.ConfigurableWebServerApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class A3SyntheticReservationImportProvisioningRunnerTest {
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
