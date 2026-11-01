package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.AdminEventDto;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 관리자 접근 가능 공연 목록 OpenAPI 계약이 실제 DTO, 오류 코드, CI 필수 MySQL 단계와 일치하는지 확인합니다. */
class AdminEventOpenApiContractTest {
    @Test
    void contractMatchesDtoAndDocumentsTenantSelectionBlock() throws IOException {
        Map<?, ?> document = yaml("docs/api/admin-events-v1.yaml");
        Map<?, ?> operation = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) document.get("paths"))
                .get("/api/v1/admin/events")).get("get");
        assertThat(keys((Map<?, ?>) operation.get("responses")))
                .contains("200", "400", "401", "403", "404", "409", "503");
        List<?> parameters = (List<?>) operation.get("parameters");
        assertThat(parameters.stream().map(parameter -> String.valueOf(((Map<?, ?>) parameter).get("name"))).toList())
                .containsExactly("page", "pageSize");
        Map<?, ?> schemas = (Map<?, ?>) ((Map<?, ?>) document.get("components")).get("schemas");
        assertProperties(schemas, "Event", AdminEventDto.Event.class);
        assertProperties(schemas, "EventPage", AdminEventDto.Page.class);
        assertThat(((Map<?, ?>) ((Map<?, ?>) schemas.get("Problem")).get("properties")).get("code").toString())
                .contains("TENANT_SELECTION_REQUIRED", "DEPENDENCY_UNAVAILABLE", "FORBIDDEN");
        assertThat(document.toString()).contains("DEC-02");
        verifyReferences(document, document);
    }

    @Test
    void mysqlReadTestsAreRequiredInCi() throws IOException {
        Map<?, ?> jobs = (Map<?, ?>) yaml(".github/workflows/backend-ci.yml").get("jobs");
        List<?> steps = (List<?>) ((Map<?, ?>) jobs.get("mysql8-integration")).get("steps");
        assertThat(steps.stream().map(step -> ((Map<?, ?>) step).get("run"))
                .filter(String.class::isInstance).map(String.class::cast).toList())
                .anyMatch(command -> command.contains("AdminEventReadMySqlTest"))
                .anyMatch(command -> command.contains("check_junit_results.py admin-event-read"));
    }

    private static void assertProperties(Map<?, ?> schemas, String name, Class<? extends Record> type) {
        Map<?, ?> properties = (Map<?, ?>) ((Map<?, ?>) schemas.get(name)).get("properties");
        assertThat(keys(properties)).as(name).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList());
    }

    private static Map<?, ?> yaml(String file) throws IOException {
        try (var input = Files.newInputStream(Path.of(file))) {
            return (Map<?, ?>) new Yaml().load(input);
        }
    }

    private static List<String> keys(Map<?, ?> map) {
        return map.keySet().stream().map(Object::toString).toList();
    }

    private static void verifyReferences(Object value, Map<?, ?> document) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("$ref") instanceof String ref) {
                Object target = document;
                for (String part : ref.substring(2).split("/")) {
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
