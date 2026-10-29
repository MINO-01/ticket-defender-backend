package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CaseW08bOpenApiContractTest {
    @Test
    void documentsOnlyReviewReceiptDecisionAndWithdrawWithoutIssuance() throws IOException {
        Map<?, ?> document = yaml("docs/api/case-w08b-v1.yaml");
        assertThat(document.get("x-contract-status")).isEqualTo("IMPLEMENTED_W08B");
        Map<?, ?> paths = (Map<?, ?>) document.get("paths");
        assertThat(paths.keySet().stream().map(Object::toString).toList()).containsExactlyInAnyOrder(
                "/api/v1/approval-requests/{approvalRequestId}/review-package",
                "/api/v1/approval-requests/{approvalRequestId}/decision",
                "/api/v1/approval-requests/{approvalRequestId}/withdraw");
        assertResponses(paths, "/api/v1/approval-requests/{approvalRequestId}/review-package", "get",
                "200", "400", "401", "403", "404", "409", "422", "503");
        assertResponses(paths, "/api/v1/approval-requests/{approvalRequestId}/review-package", "post",
                "201", "400", "401", "403", "404", "409", "422", "503");
        assertResponses(paths, "/api/v1/approval-requests/{approvalRequestId}/decision", "post",
                "201", "400", "401", "403", "404", "409", "422", "503");
        assertResponses(paths, "/api/v1/approval-requests/{approvalRequestId}/withdraw", "post",
                "200", "400", "401", "403", "404", "409", "422", "503");
        Map<?, ?> schemas = (Map<?, ?>) ((Map<?, ?>) document.get("components")).get("schemas");
        assertThat(schemas.get("ReviewPackage").toString())
                .doesNotContain("objectKey", "imageBytes", "credentials", "downloadUrl");
        assertThat(schemas.get("ReviewReceiptIssued").toString())
                .contains("reviewReceiptToken").doesNotContain("tokenSha256");
        assertThat(schemas.get("DecisionView").toString()).contains("BLOCKED");
        verifyReferences(document, document);
    }

    @Test
    void ciRequiresW08bMysqlAndRealLockedObjectProof() throws IOException {
        Map<?, ?> workflow = yaml(".github/workflows/backend-ci.yml");
        Map<?, ?> jobs = (Map<?, ?>) workflow.get("jobs");
        Map<?, ?> mysql = (Map<?, ?>) jobs.get("mysql8-integration");
        Map<?, ?> minio = (Map<?, ?>) jobs.get("minio-integration");
        assertStep(mysql, "CaseW08bMigrationMySqlTest", "case-w08b-migration");
        assertStep(mysql, "CaseW08bSchemaValidateMySqlTest", "case-w08b-schema");
        assertStep(mysql, "CaseW08bConcurrentMySqlTest", "case-w08b-concurrency");
        assertStep(minio, "CaseW08bMigrationMySqlTest", "case-w08b-migration");
        assertStep(minio, "CaseW07MinioMySqlTest", "case-w08b-minio-mysql");
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
