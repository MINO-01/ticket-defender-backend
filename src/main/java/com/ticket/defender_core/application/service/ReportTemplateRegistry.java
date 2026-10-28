package com.ticket.defender_core.application.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Component
public class ReportTemplateRegistry {
    public static final String CURRENT_VERSION = "case-approval-input-v1";
    private static final String CURRENT_RESOURCE = "report-templates/case-approval-input-v1.json";

    private final Template current;

    public ReportTemplateRegistry() {
        try (var input = new ClassPathResource(CURRENT_RESOURCE).getInputStream()) {
            String artifact = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
            this.current = new Template(CURRENT_VERSION, CaseSnapshotManifest.sha256(artifact));
        } catch (IOException missing) {
            throw new IllegalStateException("Approval report template artifact is unavailable", missing);
        }
    }

    public Template current() {
        return current;
    }

    public boolean verifies(String version, String sha256) {
        return Objects.equals(current.version(), version) && Objects.equals(current.sha256(), sha256);
    }

    public record Template(String version, String sha256) { }
}
