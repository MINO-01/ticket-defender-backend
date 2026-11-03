package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 관리자 원본 열람 OpenAPI 계약이 구현한 경로, 상태 코드, 오류 코드, CI 필수 단계와 맞는지 확인합니다. */
class AdminEvidenceContentOpenApiContractTest {
    private static final String PATH = "/api/v1/admin/fan-reports/{fanReportId}/evidence/{evidenceId}/content";

    @Test
    void contractMatchesImplementedResponsesAndExposesNoStorageLocation() throws IOException {
        Map<?, ?> document = yaml("docs/api/admin-evidence-content-v1.yaml");
        Map<?, ?> operation = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) document.get("paths")).get(PATH)).get("get");
        Map<?, ?> responses = (Map<?, ?>) operation.get("responses");
        assertThat(responses.keySet()).map(Object::toString)
                .containsExactlyInAnyOrder("200", "400", "401", "403", "404", "409", "422", "503");
        Map<?, ?> success = (Map<?, ?>) responses.get("200");
        assertThat(((Map<?, ?>) success.get("content")).keySet()).map(Object::toString)
                .containsExactlyInAnyOrder("image/png", "image/jpeg");
        assertThat(((Map<?, ?>) success.get("headers")).keySet()).map(Object::toString)
                .contains("Cache-Control", "X-Content-Type-Options", "Content-Disposition");
        Map<?, ?> problem = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) document.get("components")).get("schemas"))
                .get("Problem");
        List<?> codes = (List<?>) ((Map<?, ?>) ((Map<?, ?>) problem.get("properties")).get("code")).get("enum");
        // 서비스와 감사 마이그레이션 CHECK에 있는 실패 코드가 계약에도 있어야 합니다.
        String migration = Files.readString(Path.of("docs/database/admin-evidence-content-audit-migration.sql"));
        for (String code : List.of("RESOURCE_NOT_FOUND", "TENANT_SELECTION_REQUIRED", "SOURCE_CHANGED",
                "SOURCE_NOT_AVAILABLE", "RETENTION_EXPIRED", "UNSUPPORTED_IMAGE_TYPE", "EVIDENCE_CONTENT_TOO_LARGE",
                "OBJECT_CHANGED", "OBJECT_MISSING", "SIZE_MISMATCH", "MEDIA_TYPE_MISMATCH", "HASH_MISMATCH",
                "STORAGE_UNAVAILABLE", "EVIDENCE_CONTENT_BUSY", "AUDIT_UNAVAILABLE")) {
            assertThat(codes).as(code).map(Object::toString).contains(code);
            assertThat(migration).as(code).contains("'" + code + "'");
        }
        String schemas = ((Map<?, ?>) document.get("components")).toString() + success;
        assertThat(schemas).doesNotContain("objectKey", "objectVersion", "presigned", "downloadUrl", "bucket");
    }

    @Test
    void mysqlAndMinioTestsAreRequiredInCi() throws IOException {
        Map<?, ?> jobs = (Map<?, ?>) yaml(".github/workflows/backend-ci.yml").get("jobs");
        for (String job : List.of("mysql8-integration", "minio-integration")) {
            List<String> commands = ((List<?>) ((Map<?, ?>) jobs.get(job)).get("steps")).stream()
                    .map(step -> ((Map<?, ?>) step).get("run")).filter(String.class::isInstance)
                    .map(String.class::cast).toList();
            assertThat(commands).as(job)
                    .anyMatch(command -> command.contains("AdminEvidenceContentMigrationMySqlTest"))
                    .anyMatch(command -> command.contains("check_junit_results.py admin-evidence-content-migration"));
        }
        List<String> minio = ((List<?>) ((Map<?, ?>) jobs.get("minio-integration")).get("steps")).stream()
                .map(step -> ((Map<?, ?>) step).get("run")).filter(String.class::isInstance)
                .map(String.class::cast).toList();
        assertThat(minio).anyMatch(command -> command.contains("AdminEvidenceContentMinioMySqlTest"))
                .anyMatch(command -> command.contains("check_junit_results.py admin-evidence-content-minio-mysql"));
    }

    private static Map<?, ?> yaml(String file) throws IOException {
        try (var input = Files.newInputStream(Path.of(file))) {
            return (Map<?, ?>) new Yaml().load(input);
        }
    }
}
