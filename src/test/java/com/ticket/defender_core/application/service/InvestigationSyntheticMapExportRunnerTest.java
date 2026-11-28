package com.ticket.defender_core.application.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InvestigationSyntheticMapExportRunnerTest {
    private final Environment environment = mock(Environment.class);
    private final SyntheticReservationImportGate syntheticGate = mock(SyntheticReservationImportGate.class);
    private final InvestigationReferenceCipher cipher = mock(InvestigationReferenceCipher.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final InvestigationSyntheticMapExportRunner runner =
            new InvestigationSyntheticMapExportRunner(environment, syntheticGate, cipher, jdbc);

    @Test
    void refusesWebModeBeforeAccessingSyntheticScopeOrDatabase() throws Exception {
        when(environment.getProperty("spring.main.web-application-type", "servlet")).thenReturn("servlet");

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("INVESTIGATION_MAP_EXPORT_REQUIRES_NON_WEB_MODE");

        verifyNoInteractions(syntheticGate, cipher, jdbc);
    }

    @Test
    void refusesNonSyntheticKeySourceBeforeAccessingScopeOrDatabase() throws Exception {
        when(environment.getProperty("spring.main.web-application-type", "servlet")).thenReturn("none");
        when(cipher.enabled()).thenReturn(true);
        when(cipher.syntheticConfigSource()).thenReturn(false);

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("INVESTIGATION_MAP_EXPORT_SYNTHETIC_KEY_REQUIRED");

        verifyNoInteractions(syntheticGate, jdbc);
    }

    @Test
    void refusesRepositoryOutputPathBeforeReadingRowsOrCreatingFiles() throws Exception {
        UUID tenant = UUID.fromString("7a7626f7-b313-43ee-97ae-822069ad78f0");
        UUID event = UUID.fromString("7a7626f7-b313-43ee-97ae-822069ad78f1");
        String output = Path.of(System.getProperty("user.dir"),
                "src/test/java/com/ticket/defender_core/application/service/InvestigationSyntheticMapExportRunnerTest.java").toString();
        when(environment.getProperty("spring.main.web-application-type", "servlet")).thenReturn("none");
        when(environment.getRequiredProperty("ticket-defender.investigation-reveal.synthetic-map-export.tenant-id"))
                .thenReturn(tenant.toString());
        when(environment.getRequiredProperty("ticket-defender.investigation-reveal.synthetic-map-export.event-id"))
                .thenReturn(event.toString());
        when(environment.getProperty("ticket-defender.investigation-reveal.synthetic-map-export.output"))
                .thenReturn(output);
        when(cipher.enabled()).thenReturn(true);
        when(cipher.syntheticConfigSource()).thenReturn(true);

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("INVESTIGATION_MAP_EXPORT_PATH_UNSAFE");

        verifyNoInteractions(jdbc);
    }
}
