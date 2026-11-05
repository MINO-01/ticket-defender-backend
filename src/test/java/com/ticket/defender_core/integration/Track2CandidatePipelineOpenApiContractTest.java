package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Track2CandidatePipelineOpenApiContractTest {
    @Test
    void contractSeparatesImmutableCandidateRunsFromListingAnalysisAndPii() throws IOException {
        Map<?, ?> document = yaml("docs/api/track2-candidate-pipeline-v1.yaml");
        Map<?, ?> paths = (Map<?, ?>) document.get("paths");
        assertThat(paths.keySet().stream().map(String::valueOf).toList()).contains(
                "/api/v1/admin/events/{eventId}/verified-performances",
                "/api/v1/admin/events/{eventId}/reservation-imports",
                "/api/v1/admin/fan-reports/{fanReportId}/evidence/{evidenceId}/listing-analyses/{analysisId}/candidate-matches");
        Map<?, ?> schemas = (Map<?, ?>) ((Map<?, ?>) document.get("components")).get("schemas");
        String candidate = schemas.get("CandidateRun").toString();
        assertThat(candidate).contains("MATCH_CANDIDATE", "NO_MATCH", "AMBIGUOUS", "UNVERIFIABLE", "LOOKUP_FAILED",
                        "ANALYSIS_FAILED", "reservationSourceVersion", "evidenceSha256", "analysisResponseSha256")
                .doesNotContain("reservationReference", "accountId", "reservationRefHmac");
        String importResponse = schemas.get("ImportResult").toString();
        assertThat(importResponse).doesNotContain("reservationReference", "accountId", "reservationRefHmac");
        String revisePerformance = schemas.get("RevisePerformance").toString();
        assertThat(revisePerformance).contains("verificationBasis", "sourceVersion", "expectedRevision")
                .doesNotContain("performanceRef");
        assertThat(document.toString()).contains("complete reservation snapshot", "CANDIDATE_LOOKUP_FAILED_UNRECORDED");
        String workflow = Files.readString(Path.of(".github/workflows/backend-ci.yml"));
        String junitGuard = Files.readString(Path.of(".github/scripts/check_junit_results.py"));
        assertThat(workflow).contains("Track2CandidatePipelineMigrationMySqlTest",
                "Track2CandidatePipelineSchemaValidateMySqlTest", "Track2CandidatePipelineHttpMySqlTest",
                "Track2CandidatePipelineConcurrentMySqlTest", "track2-candidate-concurrency");
        assertThat(junitGuard).contains("Track2CandidatePipelineConcurrentMySqlTest",
                "track2-candidate-concurrency");
        verifyReferences(document, document);
    }

    @Test
    void legacyListingAnalysisContractDoesNotClaimCandidateRunsOverwriteIt() throws IOException {
        Map<?, ?> document = yaml("docs/api/track2-listing-analysis-v1.yaml");
        Map<?, ?> schemas = (Map<?, ?>) ((Map<?, ?>) document.get("components")).get("schemas");
        assertThat(schemas.get("CandidateMatch").toString()).contains("NOT_LINKED", "legacy");
    }

    private static Map<?, ?> yaml(String file) throws IOException {
        try (var input = Files.newInputStream(Path.of(file))) {
            return (Map<?, ?>) new Yaml().load(input);
        }
    }

    private static void verifyReferences(Object value, Map<?, ?> document) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("$ref") instanceof String ref) {
                assertThat(ref).startsWith("#/");
                Object target = document;
                for (String part : ref.substring(2).split("/")) {
                    assertThat(target).isInstanceOf(Map.class);
                    target = ((Map<?, ?>) target).get(part);
                    assertThat(target).as(ref).isNotNull();
                }
            }
            map.values().forEach(child -> verifyReferences(child, document));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> verifyReferences(child, document));
        }
    }
}
