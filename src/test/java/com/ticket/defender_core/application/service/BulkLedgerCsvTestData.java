package com.ticket.defender_core.application.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class BulkLedgerCsvTestData {
    private BulkLedgerCsvTestData() { }

    public static void writeFullSnapshot(OutputStream output, UUID eventId, UUID performanceId, int rows) throws IOException {
        output.write("event_id,performance_id,reservation_reference,ticket_reference,account_reference,purchased_at,ticket_quantity\n"
                .getBytes(StandardCharsets.UTF_8));
        for (int index = 0; index < rows; index++)
            output.write((eventId + "," + performanceId + ",r" + index + ",t" + index + ",a" + index
                    + ",2026-11-13T10:00:00Z,1\n").getBytes(StandardCharsets.UTF_8));
    }
}
