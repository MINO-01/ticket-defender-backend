package com.ticket.defender_core.application.service;

/** Permanent integrity conflict: the same source row was replayed with different HMAC-only content. */
public final class BulkLedgerStagingConflictException extends RuntimeException {
    public BulkLedgerStagingConflictException(Throwable cause) { super("BULK_STAGING_ROW_CONFLICT", cause); }
}
