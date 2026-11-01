package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.AdminFanReportDto;
import com.ticket.defender_core.domain.FanReport;
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

/** 관리자 팬 제보 조회 OpenAPI 계약이 실제 DTO와 상태 값, CI 필수 MySQL 단계와 일치하는지 확인합니다. */
class AdminFanReportOpenApiContractTest {
    @Test
    void contractMatchesDtoFieldsStatusesAndPrivacyBoundaries() throws IOException {
        Map<?, ?> document = yaml("docs/api/admin-fan-report-read-v1.yaml");
        Map<?, ?> paths = (Map<?, ?>) document.get("paths");
        Map<?, ?> list = (Map<?, ?>) ((Map<?, ?>) paths.get("/api/v1/admin/fan-reports")).get("get");
        Map<?, ?> detail = (Map<?, ?>) ((Map<?, ?>) paths.get("/api/v1/admin/fan-reports/{fanReportId}")).get("get");
        for (Map<?, ?> operation : List.of(list, detail)) {
            assertThat(keys((Map<?, ?>) operation.get("responses")))
                    .contains("200", "400", "401", "403", "404", "503");
        }
        Map<?, ?> schemas = (Map<?, ?>) ((Map<?, ?>) document.get("components")).get("schemas");
        assertProperties(schemas, "FanReportSummary", AdminFanReportDto.Summary.class);
        assertProperties(schemas, "FanReportPage", AdminFanReportDto.Page.class);
        assertProperties(schemas, "Evidence", AdminFanReportDto.Evidence.class);
        assertProperties(schemas, "FanReportDetail", AdminFanReportDto.Detail.class);
        assertThat((List<?>) ((Map<?, ?>) schemas.get("IntakeStatus")).get("enum")).map(Object::toString)
                .containsExactlyElementsOf(Arrays.stream(FanReport.IntakeStatus.values()).map(Enum::name).toList());
        String allSchemas = schemas.toString();
        assertThat(allSchemas).doesNotContain("objectKey", "objectVersion", "receiptNumber", "Digest", "tenantId",
                "previewUrl", "downloadUrl");
        verifyReferences(document, document);
    }

    @Test
    void mysqlReadTestsAreRequiredInCi() throws IOException {
        Map<?, ?> jobs = (Map<?, ?>) yaml(".github/workflows/backend-ci.yml").get("jobs");
        List<?> steps = (List<?>) ((Map<?, ?>) jobs.get("mysql8-integration")).get("steps");
        assertThat(steps.stream().map(step -> ((Map<?, ?>) step).get("run"))
                .filter(String.class::isInstance).map(String.class::cast).toList())
                .anyMatch(command -> command.contains("AdminFanReportReadMySqlTest"))
                .anyMatch(command -> command.contains("check_junit_results.py admin-fan-report-read"));
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
