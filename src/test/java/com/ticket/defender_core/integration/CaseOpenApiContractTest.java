package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CaseOpenApiContractTest {
    private static final Path SPEC = Path.of("docs/api/case-foundation-v1.yaml");

    @Test
    void allSevenCaseOperationsDeclareBadRequestsAndResolveLocalReferences() throws IOException {
        Map<?, ?> document;
        try (var input = Files.newInputStream(SPEC)) {
            document = (Map<?, ?>) new Yaml().load(input);
        }
        Map<?, ?> paths = (Map<?, ?>) document.get("paths");
        for (Operation operation : List.of(
                new Operation("/api/v1/cases", "post", "201", "400", "401", "403", "404", "409", "422", "503"),
                new Operation("/api/v1/cases", "get", "200", "400", "401", "403", "404"),
                new Operation("/api/v1/cases/{caseId}", "get", "200", "400", "401", "403", "404"),
                new Operation("/api/v1/cases/{caseId}/sources", "get", "200", "400", "401", "403", "404"),
                new Operation("/api/v1/cases/{caseId}/activities", "get", "200", "400", "401", "403", "404"),
                new Operation("/api/v1/cases/{caseId}/transitions", "post", "200", "400", "401", "403", "404", "409", "422"),
                new Operation("/api/v1/cases/{caseId}/assignee", "put", "200", "400", "401", "403", "404", "409", "422"))) {
            Map<?, ?> path = (Map<?, ?>) paths.get(operation.path());
            assertThat(path).as(operation.path()).isNotNull();
            Map<?, ?> request = (Map<?, ?>) path.get(operation.method());
            assertThat(request).as(operation.method() + " " + operation.path()).isNotNull();
            Map<?, ?> responses = (Map<?, ?>) request.get("responses");
            assertThat(responses.keySet().stream().map(Object::toString).toList())
                    .contains(operation.codes());
            assertThat(((Map<?, ?>) responses.get("400")).get("$ref"))
                    .isEqualTo("#/components/responses/Invalid");
        }
        verifyLocalReferences(document, document);
    }

    private static void verifyLocalReferences(Object value, Map<?, ?> document) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("$ref") instanceof String reference) {
                assertThat(reference).startsWith("#/");
                Object target = document;
                for (String part : reference.substring(2).split("/")) {
                    assertThat(target).as(reference).isInstanceOf(Map.class);
                    target = ((Map<?, ?>) target).get(part.replace("~1", "/").replace("~0", "~"));
                    assertThat(target).as(reference).isNotNull();
                }
            }
            map.values().forEach(child -> verifyLocalReferences(child, document));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> verifyLocalReferences(child, document));
        }
    }

    private record Operation(String path, String method, String... codes) { }
}
