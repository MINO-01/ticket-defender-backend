package com.ticket.defender_core.application.service;

import com.ticket.defender_core.domain.BulkLedgerImportJob;
import com.ticket.defender_core.domain.BulkLedgerImportScope;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkLedgerCsvStagingParserTest {
    @Test
    void streamsQuotedFullSnapshotAndKeepsPlainReferencesOutOfResult() {
        UUID tenant = UUID.randomUUID(), event = UUID.randomUUID(), performance = UUID.randomUUID();
        String csv = "\ufeffevent_id,performance_id,reservation_reference,ticket_reference,account_reference,purchased_at,ticket_quantity\n"
                + event + "," + performance + ",\"reservation,with-comma\",ticket-1,account-1,2026-11-13T10:00:00Z,2\n";
        List<BulkLedgerCsvStagingParser.ParsedRow> rows = new ArrayList<>();
        var result = new BulkLedgerCsvStagingParser().parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                BulkLedgerImportJob.Mode.FULL_SNAPSHOT, tenant, List.of(new BulkLedgerImportScope(UUID.randomUUID(), tenant, event, performance,
                        LocalDateTime.now(), 1, null, null)), new TenantPurposeReferenceHasher("x".repeat(32), "v1"),
                new TenantPurposeReferenceHasher("x".repeat(32), "TICKET", "v1"), new TenantPurposeReferenceHasher("x".repeat(32), "ACCOUNT", "v1"), rows::add);
        assertThat(result.rows()).isEqualTo(1);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.reservationToken()).hasSize(64).isNotEqualTo("reservation,with-comma");
            assertThat(row.ticketToken()).hasSize(64).isNotEqualTo("ticket-1");
        });
    }

    @Test
    void rejectsUndeclaredScopeWithoutReturningSourceValues() {
        String csv = "event_id,performance_id,reservation_reference,ticket_reference,account_reference,purchased_at,ticket_quantity\n"
                + UUID.randomUUID() + "," + UUID.randomUUID() + ",r,t,a,2026-11-13T10:00:00Z,1\n";
        UUID tenant = UUID.randomUUID();
        assertThatThrownBy(() -> new BulkLedgerCsvStagingParser().parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                BulkLedgerImportJob.Mode.FULL_SNAPSHOT, tenant, List.of(), new TenantPurposeReferenceHasher("x".repeat(32), "v1"),
                new TenantPurposeReferenceHasher("x".repeat(32), "TICKET", "v1"), new TenantPurposeReferenceHasher("x".repeat(32), "ACCOUNT", "v1"), row -> { }))
                .isInstanceOf(BulkLedgerCsvValidationException.class).hasMessage("BULK_CSV_SCOPE_NOT_DECLARED");
    }

    @Test
    void handlesFiftyThousandSyntheticRowsFromBoundedPipe() throws Exception {
        UUID tenant = UUID.randomUUID(), event = UUID.randomUUID(), performance = UUID.randomUUID();
        try (PipedOutputStream writer = new PipedOutputStream(); PipedInputStream input = new PipedInputStream(writer, 8 * 1024)) {
            Thread producer = new Thread(() -> {
                try (writer) {
                    writer.write("event_id,performance_id,reservation_reference,ticket_reference,account_reference,purchased_at,ticket_quantity\n".getBytes(StandardCharsets.UTF_8));
                    for (int index = 0; index < 50_000; index++)
                        writer.write((event + "," + performance + ",r" + index + ",t" + index + ",a" + index + ",2026-11-13T10:00:00Z,1\n").getBytes(StandardCharsets.UTF_8));
                } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
            });
            producer.start(); AtomicLong seen = new AtomicLong();
            var result = new BulkLedgerCsvStagingParser().parse(input, BulkLedgerImportJob.Mode.FULL_SNAPSHOT, tenant,
                    List.of(new BulkLedgerImportScope(UUID.randomUUID(), tenant, event, performance, LocalDateTime.now(), 50_000, null, null)),
                    new TenantPurposeReferenceHasher("x".repeat(32), "v1"), new TenantPurposeReferenceHasher("x".repeat(32), "TICKET", "v1"),
                    new TenantPurposeReferenceHasher("x".repeat(32), "ACCOUNT", "v1"), row -> seen.incrementAndGet());
            producer.join();
            assertThat(result.rows()).isEqualTo(50_000); assertThat(seen).hasValue(50_000);
        }
    }
}
