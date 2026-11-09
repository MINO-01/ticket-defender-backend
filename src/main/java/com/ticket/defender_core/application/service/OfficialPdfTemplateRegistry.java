package com.ticket.defender_core.application.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Registry for the server-owned official PDF layout artifact, independent of approval input JSON. */
@Component
public class OfficialPdfTemplateRegistry {
    public static final String CURRENT_VERSION = "official-case-pdf-v1";
    private static final String CURRENT_RESOURCE = "official-pdf-templates/official-case-pdf-v1.xhtml";
    public static final String DEMO_V1_VERSION = "official-case-pdf-demo-v1";
    private static final String DEMO_V1_RESOURCE = "official-pdf-templates/official-case-pdf-demo-v1.xhtml";

    private final Template current;
    private final Template demoV1;

    public OfficialPdfTemplateRegistry() {
        try (var input = new ClassPathResource(CURRENT_RESOURCE).getInputStream()) {
            String artifact = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            this.current = new Template(CURRENT_VERSION, CaseSnapshotManifest.sha256(artifact), artifact);
            try (var demo = new ClassPathResource(DEMO_V1_RESOURCE).getInputStream()) {
                String demoArtifact = new String(demo.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
                this.demoV1 = new Template(DEMO_V1_VERSION, CaseSnapshotManifest.sha256(demoArtifact), demoArtifact);
            }
        } catch (IOException missing) {
            throw new IllegalStateException("Official PDF template artifact is unavailable", missing);
        }
    }

    public Template current() { return current; }
    public Template demoV1() { return demoV1; }

    public boolean verifies(String version, String sha256) {
        return (Objects.equals(current.version(), version) && Objects.equals(current.sha256(), sha256))
                || (Objects.equals(demoV1.version(), version) && Objects.equals(demoV1.sha256(), sha256));
    }

    /** Returns the retained artifact matching the version and SHA-256 pinned on the issuance. */
    public Template pinned(String version, String sha256) {
        if (!verifies(version, sha256))
            throw new CaseApiException(HttpStatus.CONFLICT, "OFFICIAL_TEMPLATE_CHANGED");
        return Objects.equals(demoV1.version(), version) ? demoV1 : current;
    }

    public record Template(String version, String sha256, String artifact) { }
}
