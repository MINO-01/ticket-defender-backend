package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Track1SeatInvestigationOpenApiContractTest {
    @Test
    void contractKeepsTheExistingDetailParametersAndDefinesOnlySafeSeatVerificationFields() throws IOException {
        Map<?, ?> document;
        try (var input = Files.newInputStream(Path.of("docs/api/track1-seat-investigation-v1.yaml"))) {
            document = new Yaml().load(input);
        }
        Map<?, ?> detail = (Map<?, ?>) ((Map<?, ?>) document.get("paths"))
                .get("/api/v1/admin/track1/clusters/detail");
        Map<?, ?> get = (Map<?, ?>) detail.get("get");
        List<?> parameters = (List<?>) get.get("parameters");

        assertThat(parameters).hasSize(5);
        assertThat(parameters.toString()).contains("eventId", "analysisRequestId", "clusterId", "performanceId", "sourceVersion");
        Map<?, ?> schemas = (Map<?, ?>) ((Map<?, ?>) document.get("components")).get("schemas");
        Map<?, ?> context = (Map<?, ?>) schemas.get("VerificationContext");
        assertThat(context.toString()).contains("NOT_REQUESTED", "CURRENT_COMPLETE", "LEDGER_UNAVAILABLE",
                "RESERVATION_REFERENCE");
        Map<?, ?> member = (Map<?, ?>) schemas.get("ClusterDetail");
        assertThat(member.toString()).doesNotContain("reservationReference", "reservationNo", "accountReference",
                "accountId", "HMAC");
    }
}
