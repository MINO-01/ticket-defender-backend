package com.ticket.defender_core.application.service;

/** A codes-only rejection. Never place a CSV value or a plain identifier in this exception. */
public final class BulkLedgerCsvValidationException extends RuntimeException {
    private final long rowNumber;
    private final String code;

    public BulkLedgerCsvValidationException(long rowNumber, String code) {
        super(code);
        this.rowNumber = rowNumber;
        this.code = code;
    }

    public long rowNumber() { return rowNumber; }
    public String code() { return code; }
}
