package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CaseW06OpenApiContractTest {
    @Test
    void verificationAndEvaluationDocumentActualErrorAndPrivacyBoundaries() throws IOException {
        Map<?, ?> document;
        try (var input = Files.newInputStream(Path.of("docs/api/case-w06-v1.yaml"))) {
            document = (Map<?, ?>) new Yaml().load(input);
        }
        Map<?, ?> paths = (Map<?, ?>) document.get("paths");
        Map<?, ?> verification = (Map<?, ?>) paths.get("/api/v1/cases/{caseId}/evidence-verifications");
        for (String method : List.of("post", "get")) {
            Map<?, ?> operation = (Map<?, ?>) verification.get(method);
            Map<?, ?> responses = (Map<?, ?>) operation.get("responses");
            assertThat(responses.keySet().stream().map(Object::toString).toList())
                    .contains(method.equals("post") ? "201" : "200", "400", "401", "403", "404",
                            "409", "422", "503");
        }
        Map<?, ?> assessment = (Map<?, ?>) ((Map<?, ?>) paths.get(
                "/api/v1/cases/{caseId}/integrity-evaluation")).get("get");
        assertThat(((Map<?, ?>) assessment.get("responses")).keySet().stream().map(Object::toString).toList())
                .contains("200", "400", "401", "403", "404", "409", "422", "503");
        Map<?, ?> schemas = (Map<?, ?>) ((Map<?, ?>) document.get("components")).get("schemas");
        assertThat(((Map<?, ?>) schemas.get("Verification")).toString()).doesNotContain("objectKey", "imageBytes");
        assertThat(((Map<?, ?>) schemas.get("Evaluation")).toString()).contains("BLOCKED");
        verifyReferences(document, document);
    }

    @Test
    void w06RequiredMysqlAndMinioTestsCannotBeSkippedInCi() throws IOException {
        Map<?, ?> workflow;
        try (var input = Files.newInputStream(Path.of(".github/workflows/backend-ci.yml"))) {
            workflow = (Map<?, ?>) new Yaml().load(input);
        }
        Map<?, ?> jobs = (Map<?, ?>) workflow.get("jobs");
        assertSteps((Map<?, ?>) jobs.get("mysql8-integration"),
                "CaseW06MigrationMySqlTest", "case-w06-migration");
        assertSteps((Map<?, ?>) jobs.get("mysql8-integration"),
                "CaseW06SchemaValidateMySqlTest", "case-w06-schema");
        assertSteps((Map<?, ?>) jobs.get("mysql8-integration"),
                "CaseW06ConcurrentMySqlTest", "case-w06-concurrency");
        assertSteps((Map<?, ?>) jobs.get("minio-integration"),
                "CaseW06MinioMySqlTest", "case-w06-minio-mysql");
        assertSteps((Map<?, ?>) jobs.get("minio-integration"),
                "S3EvidenceIntegrityVersionedMinioTest", "case-w06-versioned-minio");
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
                    target = ((Map<?, ?>) target).get(part.replace("~1", "/").replace("~0", "~"));
                    assertThat(target).as(ref).isNotNull();
                }
            }
            map.values().forEach(child -> verifyReferences(child, document));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> verifyReferences(child, document));
        }
    }
}
