package com.ticket.defender_core.adapter.in.web.dto;

import java.util.List;
import java.util.UUID;

/** Read-only convergence of persisted Track 1 members and an immutable Track 2 candidate run. */
public final class Track1CandidateLinkDto {
    private Track1CandidateLinkDto() { }

    public enum Status { LINKED, NO_TRACK1_LINK, NOT_APPLICABLE, LEDGER_UNAVAILABLE }
    public enum LedgerSeatStatus { FOUND, NOT_IN_LEDGER, LEDGER_UNAVAILABLE }
    /** LINKED is an opaque reservation-reference convergence signal, never a ticket-authenticity or fraud finding. */
    public enum LinkBasis { RESERVATION_REFERENCE }

    /** auditId is an opaque existing audit record id; reservation references and HMAC values are never exposed. */
    public record Seat(String zone, String row, String seat) { }
    public record LedgerSeat(Long auditId, LedgerSeatStatus status, String zone, String row, String seat,
                             List<Seat> seats) {
        public LedgerSeat(Long auditId, LedgerSeatStatus status, String zone, String row, String seat) {
            this(auditId, status, zone, row, seat, zone == null ? List.of() : List.of(new Seat(zone, row, seat)));
        }
    }

    public record Item(UUID graphSourceId, UUID candidateSourceId, UUID candidateRunId, Status status,
                       List<LedgerSeat> ledgerSeats, LinkBasis linkBasis) { }

    public record View(Status status, List<Item> items) { }
}
