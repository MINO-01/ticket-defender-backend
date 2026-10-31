package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Track2ListingAnalysisOpenApiContractTest {
    @Test
    void contractDocumentsActualStatusesAndPrivacyBoundaries() throws IOException {
        Map<?, ?> document = yaml("docs/api/track2-listing-analysis-v1.yaml");
        Map<?, ?> path = (Map<?, ?>) ((Map<?, ?>) document.get("paths"))
                .get("/api/v1/admin/fan-reports/{fanReportId}/evidence/{evidenceId}/listing-analyses");
        assertThat(keys((Map<?, ?>) ((Map<?, ?>) path.get("post")).get("responses")))
                .contains("201", "400", "401", "403", "404", "409", "422", "502", "503", "504");
        assertThat(keys((Map<?, ?>) ((Map<?, ?>) path.get("get")).get("responses")))
                .contains("200", "400", "401", "403", "404");
        Map<?, ?> schemas = (Map<?, ?>) ((Map<?, ?>) document.get("components")).get("schemas");
        assertThat(schemas.get("Analysis").toString()).doesNotContain("objectKey", "objectMarker", "imageBase64");
        assertThat(schemas.get("Analysis").toString()).contains("COMPLETED", "PARTIAL", "UNVERIFIABLE", "FAILED");
        assertThat(schemas.get("Field").toString()).contains("FOUND", "UNKNOWN", "AMBIGUOUS");
        assertThat(schemas.get("CandidateMatch").toString()).contains("NOT_LINKED").doesNotContain("enum=[MATCH");
        verifyReferences(document, document);
    }

    @Test
    void requiredMysqlAndMinioTestsCannotBeSkippedInCi() throws IOException {
        Map<?, ?> jobs = (Map<?, ?>) yaml(".github/workflows/backend-ci.yml").get("jobs");
        Map<?, ?> mysql = (Map<?, ?>) jobs.get("mysql8-integration");
        Map<?, ?> minio = (Map<?, ?>) jobs.get("minio-integration");
        assertSteps(mysql, "Track2ListingAnalysisMigrationMySqlTest", "track2-listing-migration");
        assertSteps(mysql, "Track2ListingAnalysisSchemaValidateMySqlTest", "track2-listing-schema");
        assertSteps(mysql, "Track2ListingAnalysisConcurrentMySqlTest", "track2-listing-concurrency");
        assertSteps(minio, "Track2ListingAnalysisMigrationMySqlTest", "track2-listing-migration");
        assertSteps(minio, "Track2ListingAnalysisMinioMySqlTest", "track2-listing-minio-mysql");
    }

    private static Map<?, ?> yaml(String file) throws IOException {
        try (var input = Files.newInputStream(Path.of(file))) {
            return (Map<?, ?>) new Yaml().load(input);
        }
    }

    private static List<String> keys(Map<?, ?> map) {
        return map.keySet().stream().map(Object::toString).toList();
    }

    private static void assertSteps(Map<?, ?> job, String test, String profile) {
        List<?> steps = (List<?>) job.get("steps");
        assertThat(steps.stream().map(step -> ((Map<?, ?>) step).get("run"))
                .filter(String.class::isInstance).map(String.class::cast).toList())
                .anyMatch(command -> command.contains(test))
                .anyMatch(command -> command.contains("check_junit_results.py " + profile));
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
