package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CaseW09OpenApiContractTest {
    @Test
    void documentsSeparateIssuanceArtifactAndPrivateDownloadWithoutLeakingStorageFields() throws IOException {
        Map<?, ?> document = yaml("docs/api/case-w09-official-pdf-v1.yaml");
        assertThat(document.get("x-contract-status")).isEqualTo("IMPLEMENTED_W09_SYNTHETIC_ONLY");
        Map<?, ?> paths = (Map<?, ?>) document.get("paths");
        assertThat(paths.keySet().stream().map(Object::toString).toList()).containsExactlyInAnyOrder(
                "/api/v1/approval-decisions/{approvalDecisionId}/official-pdf-eligibility",
                "/api/v1/approval-decisions/{approvalDecisionId}/official-pdf-issuances",
                "/api/v1/official-pdf-issuances/{issuanceId}",
                "/api/v1/official-pdf-issuances/{issuanceId}/retry",
                "/api/v1/official-pdf-issuances/{issuanceId}/download",
                "/api/v1/report-versions/{reportVersionId}/official-pdf-issuances");
        Map<?, ?> schemas = (Map<?, ?>) ((Map<?, ?>) document.get("components")).get("schemas");
        assertThat(schemas.get("Issuance").toString()).contains("CURRENT", "STALE", "BLOCKED", "GENERATED")
                .doesNotContain("objectKey", "objectVersion", "credentials", "downloadUrl", "reviewReceiptToken");
        assertResponses(paths, "/api/v1/approval-decisions/{approvalDecisionId}/official-pdf-issuances", "post",
                "202", "400", "401", "403", "404", "409", "422", "503");
        assertResponses(paths, "/api/v1/official-pdf-issuances/{issuanceId}/download", "get",
                "200", "401", "403", "404", "409", "503");
        verifyReferences(document, document);
    }

    @Test
    void ciRequiresW09MigrationSchemaAndPrivateMinioStorageChecks() throws IOException {
        Map<?, ?> workflow = yaml(".github/workflows/backend-ci.yml");
        Map<?, ?> jobs = (Map<?, ?>) workflow.get("jobs");
        assertStep((Map<?, ?>) jobs.get("mysql8-integration"), "CaseW09MigrationMySqlTest", "case-w09-migration");
        assertStep((Map<?, ?>) jobs.get("mysql8-integration"), "CaseW09SchemaValidateMySqlTest", "case-w09-schema");
        assertStep((Map<?, ?>) jobs.get("minio-integration"), "CaseW09MigrationMySqlTest", "case-w09-migration");
        assertStep((Map<?, ?>) jobs.get("minio-integration"), "S3OfficialPdfStorageMinioTest",
                "case-w09-minio-storage");
        assertStep((Map<?, ?>) jobs.get("mysql8-integration"), "CaseW09ConcurrentMySqlTest", "case-w09-concurrency");
        assertStep((Map<?, ?>) jobs.get("minio-integration"), "CaseW09MinioMySqlTest", "case-w09-minio-mysql");
    }

    private static Map<?, ?> yaml(String file) throws IOException {
        try (var input = Files.newInputStream(Path.of(file))) {
            return (Map<?, ?>) new Yaml().load(input);
        }
    }

    private static void assertResponses(Map<?, ?> paths, String pathName, String method, String... statuses) {
        Map<?, ?> operation = (Map<?, ?>) ((Map<?, ?>) paths.get(pathName)).get(method);
        Map<?, ?> responses = (Map<?, ?>) operation.get("responses");
        assertThat(responses.keySet().stream().map(Object::toString).toList()).contains(statuses);
    }

    private static void assertStep(Map<?, ?> job, String test, String profile) {
        List<?> steps = (List<?>) job.get("steps");
        List<String> commands = steps.stream().map(step -> ((Map<?, ?>) step).get("run"))
                .filter(String.class::isInstance).map(String.class::cast).toList();
        assertThat(commands).anyMatch(command -> command.contains(test));
        assertThat(commands).anyMatch(command -> command.contains("check_junit_results.py " + profile));
    }

    private static void verifyReferences(Object value, Map<?, ?> document) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("$ref") instanceof String reference) {
                assertThat(reference).startsWith("#/");
                Object target = document;
                for (String part : reference.substring(2).split("/")) {
                    assertThat(target).isInstanceOf(Map.class);
                    target = ((Map<?, ?>) target).get(part.replace("~1", "/").replace("~0", "~"));
                    assertThat(target).as(reference).isNotNull();
                }
            }
            map.values().forEach(child -> verifyReferences(child, document));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> verifyReferences(child, document));
        }
    }
}
