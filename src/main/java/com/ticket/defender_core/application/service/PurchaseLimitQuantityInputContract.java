package com.ticket.defender_core.application.service;

import com.ticket.defender_core.domain.PurchaseLimitPolicy;
import java.time.Instant;

/**
 * Internal boundary for customer-authorized purchase-limit inputs. It intentionally does not create values,
 * call FastAPI, or turn a relationship result into a limit result. Persistence/ingest adapters must retain
 * the supplied source fields before converting an INDIVIDUAL_TICKET input to the current Data #30 v2 DTO.
 */
public final class PurchaseLimitQuantityInputContract {
    private PurchaseLimitQuantityInputContract() { }
    public enum InputType { INDIVIDUAL_TICKET, ACCOUNT_LIMIT_SNAPSHOT }
    public enum TicketStatus { ACTIVE, CANCELLED, REFUNDED, UNKNOWN }
    public enum AssessmentStatus { READY_FOR_INDIVIDUAL_TICKET_V2, POLICY_NOT_CONFIRMED, QUANTITY_DATA_INCOMPLETE, ACCOUNT_SNAPSHOT_NOT_SUPPORTED_BY_DATA_V2 }

    public sealed interface Input permits IndividualTicket, AccountLimitSnapshot { InputType inputType(); }
    /** A real provider ticket/order-line fact; quantity is never inferred from a row or reservation number. */
    public record IndividualTicket(String accountReference, String reservationReference, String ticketReference,
                                   Instant purchasedAt, Integer ticketQuantity, TicketStatus ticketStatus,
                                   String sourceVersion, Instant sourceAsOf) implements Input {
        @Override public InputType inputType() { return InputType.INDIVIDUAL_TICKET; }
    }
    /**
     * A provider supplied aggregate. policyCalculationQuantity and currentEffectiveTicketQuantity are intentionally
     * distinct: the second may be absent and neither is derived by Spring. Data #30 v2 does not accept this type yet.
     */
    public record AccountLimitSnapshot(String accountReference, String scopeReference, Integer policyCalculationQuantity,
                                       Integer currentEffectiveTicketQuantity, String calculationBasisVersion,
                                       Instant asOf, String sourceVersion) implements Input {
        @Override public InputType inputType() { return InputType.ACCOUNT_LIMIT_SNAPSHOT; }
    }
    public record Assessment(AssessmentStatus status, String reason) { }

    public static Assessment assess(PurchaseLimitPolicy policy, Input input) {
        if (policy==null || !policy.hasConfirmedAccountLimitSemantics()) return new Assessment(AssessmentStatus.POLICY_NOT_CONFIRMED,"Confirmed ACCOUNT subject, scope, cancellation/refund rule, and presale handling are required");
        if (input instanceof AccountLimitSnapshot) return new Assessment(AssessmentStatus.ACCOUNT_SNAPSHOT_NOT_SUPPORTED_BY_DATA_V2,"Data #30 v2 currently accepts only individual ticket inputs");
        if (!(input instanceof IndividualTicket ticket) || blank(ticket.accountReference()) || blank(ticket.reservationReference()) || blank(ticket.ticketReference()) || ticket.purchasedAt()==null || ticket.ticketQuantity()==null || ticket.ticketQuantity()<1 || ticket.ticketStatus()==TicketStatus.UNKNOWN || blank(ticket.sourceVersion()) || ticket.sourceAsOf()==null) return new Assessment(AssessmentStatus.QUANTITY_DATA_INCOMPLETE,"Actual ticket time, quantity, status, source version, and as-of time are required");
        return new Assessment(AssessmentStatus.READY_FOR_INDIVIDUAL_TICKET_V2,"No quantity or purchase timestamp was synthesized");
    }
    private static boolean blank(String value) { return value==null || value.isBlank(); }
}
