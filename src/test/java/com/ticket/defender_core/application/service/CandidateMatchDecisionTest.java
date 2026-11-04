package com.ticket.defender_core.application.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateMatchDecisionTest {

    @Test
    void noMatchRequiresACompleteApprovedReservationSnapshot() {
        CandidateMatchDecision.Result incomplete = CandidateMatchDecision.decide(
                CandidateMatchDecision.AnalysisInput.succeeded(foundSeats()), true, false, 0);
        CandidateMatchDecision.Result complete = CandidateMatchDecision.decide(
                CandidateMatchDecision.AnalysisInput.succeeded(foundSeats()), true, true, 0);

        assertThat(incomplete.status()).isEqualTo(CandidateMatchDecision.Status.UNVERIFIABLE);
        assertThat(incomplete.reason()).isEqualTo(CandidateMatchDecision.Reason.RESERVATION_SOURCE_INCOMPLETE);
        assertThat(complete.status()).isEqualTo(CandidateMatchDecision.Status.NO_MATCH);
        assertThat(complete.reason()).isEqualTo(CandidateMatchDecision.Reason.NO_CANDIDATE_FOUND);
    }

    @Test
    void ambiguousOrUnknownSeatObservationsNeverBecomeMatches() {
        CandidateMatchDecision.Result ambiguousSeat = CandidateMatchDecision.decide(
                CandidateMatchDecision.AnalysisInput.succeeded(List.of(
                        found("VIP"), ambiguous(), found("5"))), true, true, 1);
        CandidateMatchDecision.Result unknownSeat = CandidateMatchDecision.decide(
                CandidateMatchDecision.AnalysisInput.succeeded(List.of(
                        found("VIP"), found("10"), unknown())), true, true, 1);

        assertThat(ambiguousSeat).isEqualTo(new CandidateMatchDecision.Result(
                CandidateMatchDecision.Status.UNVERIFIABLE,
                CandidateMatchDecision.Reason.SEAT_OBSERVATION_AMBIGUOUS));
        assertThat(unknownSeat).isEqualTo(new CandidateMatchDecision.Result(
                CandidateMatchDecision.Status.UNVERIFIABLE,
                CandidateMatchDecision.Reason.SEAT_OBSERVATION_UNAVAILABLE));
    }

    @Test
    void oneAndManyCandidatesAreKeptDistinctWithoutAnyMappingScore() {
        CandidateMatchDecision.Result one = CandidateMatchDecision.decide(
                CandidateMatchDecision.AnalysisInput.succeeded(foundSeats()), true, true, 1);
        CandidateMatchDecision.Result many = CandidateMatchDecision.decide(
                CandidateMatchDecision.AnalysisInput.succeeded(foundSeats()), true, true, 2);

        assertThat(one.status()).isEqualTo(CandidateMatchDecision.Status.MATCH_CANDIDATE);
        assertThat(many.status()).isEqualTo(CandidateMatchDecision.Status.AMBIGUOUS);
        assertThat(many.reason()).isEqualTo(CandidateMatchDecision.Reason.MULTIPLE_CANDIDATES);
    }

    @Test
    void failedAnalysisAndMissingPerformanceLinkStaySeparateFromNoMatch() {
        CandidateMatchDecision.Result failed = CandidateMatchDecision.decide(
                CandidateMatchDecision.AnalysisInput.failed(), true, true, 0);
        CandidateMatchDecision.Result unlinked = CandidateMatchDecision.decide(
                CandidateMatchDecision.AnalysisInput.succeeded(foundSeats()), false, true, 0);

        assertThat(failed).isEqualTo(new CandidateMatchDecision.Result(
                CandidateMatchDecision.Status.ANALYSIS_FAILED,
                CandidateMatchDecision.Reason.ANALYSIS_NOT_SUCCEEDED));
        assertThat(unlinked).isEqualTo(new CandidateMatchDecision.Result(
                CandidateMatchDecision.Status.UNVERIFIABLE,
                CandidateMatchDecision.Reason.PERFORMANCE_LINK_UNVERIFIED));
    }

    private static List<CandidateMatchDecision.SeatObservation> foundSeats() {
        return List.of(found("VIP"), found("10"), found("5"));
    }

    private static CandidateMatchDecision.SeatObservation found(String value) {
        return new CandidateMatchDecision.SeatObservation(CandidateMatchDecision.ObservationState.FOUND, value);
    }

    private static CandidateMatchDecision.SeatObservation unknown() {
        return new CandidateMatchDecision.SeatObservation(CandidateMatchDecision.ObservationState.UNKNOWN, null);
    }

    private static CandidateMatchDecision.SeatObservation ambiguous() {
        return new CandidateMatchDecision.SeatObservation(CandidateMatchDecision.ObservationState.AMBIGUOUS, null);
    }
}
