package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CaseW08aOpenApiContractTest {
    @Test
    void documentsOnlyImplementedSubmissionAndDetailWithoutSensitiveStorageFields() throws IOException {
        Map<?, ?> document = yaml("docs/api/case-w08a-v1.yaml");
        assertThat(document.get("x-contract-status")).isEqualTo("IMPLEMENTED_W08A");
        Map<?, ?> paths = (Map<?, ?>) document.get("paths");
        assertThat(paths.keySet().stream().map(Object::toString).toList()).containsExactlyInAnyOrder(
                "/api/v1/cases/{caseId}/approval-requests",
                "/api/v1/approval-requests/{approvalRequestId}");
        assertResponses(paths, "/api/v1/cases/{caseId}/approval-requests", "post",
                "201", "400", "401", "403", "404", "409", "422", "503");
        assertResponses(paths, "/api/v1/approval-requests/{approvalRequestId}", "get",
                "200", "400", "401", "403", "404", "503");
        Map<?, ?> schemas = (Map<?, ?>) ((Map<?, ?>) document.get("components")).get("schemas");
        String response = schemas.get("ApprovalRequest").toString();
        assertThat(response).contains("reportTemplateSha256", "manifestSha256", "BLOCKED")
                .doesNotContain("tenantId", "objectKey", "imageBytes", "credentials");
        assertThat(((Map<?, ?>) schemas.get("ApprovalRequest")).toString())
                .doesNotContain("APPROVAL_DECISION", "REVIEW_RECEIPT");
        verifyReferences(document, document);

        Map<?, ?> proposal = yaml("docs/api/case-approval-v1-proposal.yaml");
        assertThat(proposal.get("x-contract-status")).isEqualTo("PROPOSED_UNIMPLEMENTED");
        assertThat(proposal.get("x-w08a-implemented-contract")).isEqualTo("case-w08a-v1.yaml");
    }

    @Test
    void ciRequiresW08aMigrationSchemaConcurrencyAndRealObjectLockProof() throws IOException {
        Map<?, ?> workflow = yaml(".github/workflows/backend-ci.yml");
        Map<?, ?> jobs = (Map<?, ?>) workflow.get("jobs");
        Map<?, ?> mysql = (Map<?, ?>) jobs.get("mysql8-integration");
        Map<?, ?> minio = (Map<?, ?>) jobs.get("minio-integration");
        assertStep(mysql, "CaseW08aMigrationMySqlTest", "case-w08a-migration");
        assertStep(mysql, "CaseW08aSchemaValidateMySqlTest", "case-w08a-schema");
        assertStep(mysql, "CaseW08aConcurrentMySqlTest", "case-w08a-concurrency");
        assertStep(minio, "CaseW08aMigrationMySqlTest", "case-w08a-migration");
        assertStep(minio, "CaseW07MinioMySqlTest", "case-w08a-minio-mysql");
    }

    private static Map<?, ?> yaml(String file) throws IOException {
        try (var input = Files.newInputStream(Path.of(file))) {
            return (Map<?, ?>) new Yaml().load(input);
        }
    }

    private static void assertResponses(Map<?, ?> paths, String pathName, String method,
            String... statuses) {
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
