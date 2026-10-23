package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CaseW05OpenApiContractTest {
    @Test
    void allPreparationOperationsDocumentBoundariesAndResolveReferences() throws IOException {
        Map<?, ?> document;
        try (var input = Files.newInputStream(Path.of("docs/api/case-w05-v1.yaml"))) {
            document = (Map<?, ?>) new Yaml().load(input);
        }
        Map<?, ?> paths = (Map<?, ?>) document.get("paths");
        for (String pathName : List.of(
                "/api/v1/cases/{caseId}/evidence-snapshots",
                "/api/v1/cases/{caseId}/report-versions")) {
            Map<?, ?> path = (Map<?, ?>) paths.get(pathName);
            for (String method : List.of("post", "get")) {
                Map<?, ?> operation = (Map<?, ?>) path.get(method);
                Map<?, ?> responses = (Map<?, ?>) operation.get("responses");
                assertThat(responses.keySet().stream().map(Object::toString).toList())
                        .contains("400", "401", "403", "404", "409", "422", "503",
                                method.equals("post") ? "201" : "200");
            }
        }
        for (String pathName : List.of(
                "/api/v1/cases/{caseId}/evidence-snapshots/{snapshotId}",
                "/api/v1/report-versions/{reportVersionId}")) {
            Map<?, ?> operation = (Map<?, ?>) ((Map<?, ?>) paths.get(pathName)).get("get");
            Map<?, ?> responses = (Map<?, ?>) operation.get("responses");
            assertThat(responses.keySet().stream().map(Object::toString).toList())
                    .contains("200", "400", "401", "403", "404", "409", "422", "503");
        }
        verifyReferences(document, document);
    }

    @Test
    void ciYamlKeepsRequiredJobsAndFailsWhenW05IntegrationTestsSkip() throws IOException {
        Map<?, ?> workflow;
        try (var input = Files.newInputStream(Path.of(".github/workflows/backend-ci.yml"))) {
            workflow = (Map<?, ?>) new Yaml().load(input);
        }
        Map<?, ?> jobs = (Map<?, ?>) workflow.get("jobs");
        assertThat(jobs.keySet().stream().map(Object::toString).toList())
                .contains("java21-build", "mysql8-integration",
                "mysql8-track1-legacy", "minio-integration");
        assertStep((Map<?, ?>) jobs.get("mysql8-integration"), "CaseW05MigrationMySqlTest",
                "case-w05-migration");
        assertStep((Map<?, ?>) jobs.get("mysql8-integration"), "CaseW05ConcurrentMySqlTest",
                "case-w05-concurrency");
        assertStep((Map<?, ?>) jobs.get("minio-integration"), "CaseW05MinioMySqlTest",
                "case-w05-minio");
    }

    private static void assertStep(Map<?, ?> job, String testName, String profile) {
        List<?> steps = (List<?>) job.get("steps");
        assertThat(steps.stream().map(step -> ((Map<?, ?>) step).get("run"))
                .filter(String.class::isInstance).map(String.class::cast).toList())
                .anyMatch(command -> command.contains(testName))
                .anyMatch(command -> command.contains("check_junit_results.py " + profile));
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
