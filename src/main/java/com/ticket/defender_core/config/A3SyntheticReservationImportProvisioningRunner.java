package com.ticket.defender_core.config;

import com.ticket.defender_core.application.service.A3SyntheticReservationImportProvisioner;
import com.ticket.defender_core.application.service.A3SyntheticReservationImportProvisioner.A3SyntheticImportProvisioningException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.server.context.ConfigurableWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/** Runs only when explicitly enabled from an operator-owned A3 runtime configuration. */
@Component
@RequiredArgsConstructor
public class A3SyntheticReservationImportProvisioningRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(A3SyntheticReservationImportProvisioningRunner.class);

    private final A3SyntheticReservationImportProvisioner provisioner;
    private final ApplicationContext context;

    @Value("${ticket-defender.a3-synthetic-import-provisioning.enabled:false}")
    private boolean enabled;
    @Value("${ticket-defender.a3-synthetic-import-provisioning.execute:false}")
    private boolean execute;
    @Value("${ticket-defender.a3-synthetic-import-provisioning.tenant-id:}")
    private String tenantId;
    @Value("${ticket-defender.a3-synthetic-import-provisioning.event-id:}")
    private String eventId;
    @Value("${ticket-defender.a3-synthetic-import-provisioning.membership-id:}")
    private String membershipId;
    @Value("${ticket-defender.a3-synthetic-import-provisioning.isolation-id:}")
    private String isolationId;
    @Value("${ticket-defender.a3-synthetic-import-provisioning.expected-database-name:}")
    private String expectedDatabaseName;
    @Value("${ticket-defender.a3-synthetic-import-provisioning.fixture-path:}")
    private String fixturePath;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        if (execute && context instanceof ConfigurableWebServerApplicationContext) {
            log.error("A3 synthetic reservation import provisioning rejected: code=A3_SYNTHETIC_IMPORT_WEB_APPLICATION_FORBIDDEN");
            throw new IllegalStateException("A3_SYNTHETIC_IMPORT_WEB_APPLICATION_FORBIDDEN");
        }
        A3SyntheticReservationImportProvisioner.Request request = new A3SyntheticReservationImportProvisioner.Request(
                tenantId, eventId, membershipId, isolationId, expectedDatabaseName, fixturePath);
        try {
            A3SyntheticReservationImportProvisioner.Result result = execute
                    ? provisioner.execute(request) : provisioner.dryRun(request);
            log.info("A3 synthetic reservation import provisioning completed: mode={}, registrationCreated={}, fixtureRows={}, importStatus={}",
                    result.executed() ? "EXECUTE" : "DRY_RUN", result.registrationCreated(), result.fixtureRowCount(),
                    result.importStatus());
        } catch (A3SyntheticImportProvisioningException failed) {
            log.error("A3 synthetic reservation import provisioning rejected: code={}", failed.code());
            throw failed;
        }
    }
}
