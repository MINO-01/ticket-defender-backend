package com.ticket.defender_core.application.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** 명시적으로 켠 비웹 합성 환경에서만 계정 HMAC 대응표를 저장소 밖에 생성합니다. */
@Component
@ConditionalOnProperty(prefix = "ticket-defender.investigation-reveal.synthetic-map-export", name = "enabled", havingValue = "true")
public class InvestigationSyntheticMapExportRunner implements ApplicationRunner {
    private final Environment environment;
    private final SyntheticReservationImportGate syntheticGate;
    private final InvestigationReferenceCipher cipher;
    private final JdbcTemplate jdbc;

    public InvestigationSyntheticMapExportRunner(Environment environment, SyntheticReservationImportGate syntheticGate,
            InvestigationReferenceCipher cipher, JdbcTemplate jdbc) {
        this.environment = environment; this.syntheticGate = syntheticGate; this.cipher = cipher; this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!"none".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type", "servlet")))
            throw new IllegalStateException("INVESTIGATION_MAP_EXPORT_REQUIRES_NON_WEB_MODE");
        if (!cipher.enabled() || !cipher.syntheticConfigSource())
            throw new IllegalStateException("INVESTIGATION_MAP_EXPORT_SYNTHETIC_KEY_REQUIRED");
        UUID tenantId = parseUuid("ticket-defender.investigation-reveal.synthetic-map-export.tenant-id");
        UUID eventId = parseUuid("ticket-defender.investigation-reveal.synthetic-map-export.event-id");
        String configuredPath = environment.getProperty("ticket-defender.investigation-reveal.synthetic-map-export.output");
        if (configuredPath == null || configuredPath.isBlank()) throw new IllegalStateException("INVESTIGATION_MAP_EXPORT_PATH_REQUIRED");
        syntheticGate.requireSyntheticScope(tenantId, LocalDateTime.now(ZoneOffset.UTC));
        cipher.requireAvailableForTenant(tenantId);

        Path requestedTarget = Path.of(configuredPath).toAbsolutePath().normalize();
        Path requestedParent = requestedTarget.getParent();
        if (requestedParent == null || !Files.isDirectory(requestedParent))
            throw new IllegalStateException("INVESTIGATION_MAP_EXPORT_PATH_UNSAFE");
        Path parent = requestedParent.toRealPath();
        Path target = parent.resolve(requestedTarget.getFileName()).normalize();
        if (inRepository(target)) throw new IllegalStateException("INVESTIGATION_MAP_EXPORT_PATH_UNSAFE");
        requirePrivateAcl(parent);
        if (Files.exists(target)) throw new IllegalStateException("INVESTIGATION_MAP_EXPORT_FILE_EXISTS");

        Path temporary = parent.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        boolean created = false;
        boolean[] outputCreated = {false};
        try {
            Files.createFile(temporary);
            created = true;
            restrictFileAcl(temporary);
            final int[] count = {0};
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8, StandardOpenOption.WRITE)) {
                writer.write("account_reference_hmac,account_reference\r\n");
                jdbc.query("""
                select id,import_id,tenant_id,event_id,performance_id,source_row_no,ticket_reference_hmac,
                          reservation_reference_hmac,account_reference_hmac,reference_ciphertext,nonce,encryption_key_version
                        from (
                          select c.*,row_number() over(partition by c.account_reference_hmac,c.encryption_key_version
                            order by c.created_at desc,c.id) as rn
                          from td_investigation_reference_ciphertexts c
                          where c.tenant_id=? and c.event_id=? and c.account_reference_hmac is not null and c.ledger_version_id is not null
                ) ranked where rn=1 order by account_reference_hmac
                        """, rs -> {
                    try {
                        UUID importId = UUID.fromString(rs.getString(2));
                        UUID rowTenant = UUID.fromString(rs.getString(3));
                        UUID rowEvent = UUID.fromString(rs.getString(4));
                        UUID performance = UUID.fromString(rs.getString(5));
                        long rowNumber = rs.getLong(6);
                        String ticketHmac = rs.getString(7), reservationHmac = rs.getString(8), accountHmac = rs.getString(9);
                        var encrypted = new InvestigationReferenceCipher.Encrypted(rs.getString(12), rs.getBytes(11), rs.getBytes(10));
                        var references = cipher.decrypt(importId, rowTenant, rowEvent, performance, rowNumber,
                                ticketHmac, reservationHmac, accountHmac, encrypted);
                        String account = references.accountReference();
                        if (account == null) throw new IllegalStateException("INVESTIGATION_MAP_ACCOUNT_MISSING");
                        writer.write(csv(accountHmac)); writer.write(','); writer.write(csv(account)); writer.write("\r\n");
                        count[0]++;
                    } catch (Exception failure) {
                        throw new IllegalStateException("INVESTIGATION_MAP_EXPORT_FAILED");
                    }
                }, tenantId.toString(), eventId.toString());
            }
            Files.move(temporary, target);
            created = false;
            outputCreated[0] = true;
            jdbc.update("insert into td_investigation_reference_access_audit(id,tenant_id,event_id,actor_subject,target_type,resource_id,action,target_count,reason,accessed_at) values(?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), tenantId.toString(), eventId.toString(), "CLI:" + System.getProperty("user.name", "unknown"),
                    "MULTI_EVENT", "SYNTHETIC_MAP_EXPORT", "EXPORT", count[0], "SYNTHETIC_CORRESPONDENCE_MAP_EXPORT",
                    java.sql.Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC)));
        } catch (Exception failure) {
            if (created) Files.deleteIfExists(temporary);
            if (outputCreated[0]) Files.deleteIfExists(target);
            throw failure;
        }
    }

    private UUID parseUuid(String property) {
        try { return UUID.fromString(environment.getRequiredProperty(property)); }
        catch (RuntimeException invalid) { throw new IllegalStateException("INVESTIGATION_MAP_EXPORT_SCOPE_INVALID"); }
    }

    private static boolean inRepository(Path target) {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) return target.startsWith(current);
            current = current.getParent();
        }
        return true;
    }

    private static void requirePrivateAcl(Path parent) throws Exception {
        AclFileAttributeView view = Files.getFileAttributeView(parent, AclFileAttributeView.class);
        if (view == null) throw new IllegalStateException("INVESTIGATION_MAP_EXPORT_ACL_UNAVAILABLE");
        String owner = Files.getOwner(parent).getName();
        for (AclEntry entry : view.getAcl()) {
            if (entry.type() != AclEntryType.ALLOW) continue;
            String principal = entry.principal().getName();
            if (!principal.equalsIgnoreCase(owner) && !principal.toLowerCase(java.util.Locale.ROOT).endsWith("\\system")
                    && !principal.toLowerCase(java.util.Locale.ROOT).endsWith("\\administrators"))
                throw new IllegalStateException("INVESTIGATION_MAP_EXPORT_ACL_NOT_PRIVATE");
        }
    }

    private static void restrictFileAcl(Path file) throws Exception {
        AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (view == null) throw new IllegalStateException("INVESTIGATION_MAP_EXPORT_ACL_UNAVAILABLE");
        UserPrincipal owner = Files.getOwner(file);
        AclEntry ownerEntry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
        view.setAcl(List.of(ownerEntry));
    }

    private static String csv(String value) {
        int firstVisible = 0;
        while (firstVisible < value.length() && Character.isWhitespace(value.charAt(firstVisible))) firstVisible++;
        String safe = firstVisible < value.length() && "=+-@".indexOf(value.charAt(firstVisible)) >= 0
                ? "'" + value : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
