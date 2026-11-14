package com.ticket.defender_core.application.service;

/** Permanent integrity conflict: the same source row was replayed with different HMAC-only content. */
public final class BulkLedgerStagingConflictException extends RuntimeException {
    private final long rowNumber;
    public BulkLedgerStagingConflictException(long rowNumber) { super("BULK_STAGING_ROW_CONFLICT"); this.rowNumber = rowNumber; }
    public long rowNumber() { return rowNumber; }
}
