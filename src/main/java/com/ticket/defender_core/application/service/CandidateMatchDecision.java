package com.ticket.defender_core.application.service;

import java.util.List;

/**
 * Listing Context의 저장된 좌석 관찰과 범위가 검증된 예매 스냅샷만으로 후보 결과를 결정한다.
 * 추출 신뢰도나 VLM mappingScore는 이 경로에 존재하지 않는다.
 */
public final class CandidateMatchDecision {
    private CandidateMatchDecision() { }

    public enum Status {
        IN_PROGRESS, MATCH_CANDIDATE, NO_MATCH, AMBIGUOUS, UNVERIFIABLE, LOOKUP_FAILED, ANALYSIS_FAILED
    }

    public enum Reason {
        LOOKUP_STARTED,
        MATCHED_SINGLE_CANDIDATE,
        NO_CANDIDATE_FOUND,
        MULTIPLE_CANDIDATES,
        ANALYSIS_NOT_SUCCEEDED,
        PERFORMANCE_LINK_UNVERIFIED,
        PERFORMANCE_WITHDRAWN,
        PERFORMANCE_REVISION_CHANGED,
        RESERVATION_SOURCE_INCOMPLETE,
        RESERVATION_SOURCE_UNAVAILABLE,
        SEAT_OBSERVATION_UNAVAILABLE,
        SEAT_OBSERVATION_AMBIGUOUS,
        ANALYSIS_OR_EVIDENCE_CHANGED,
        RESERVATION_LOOKUP_FAILED
    }

    public enum ObservationState { FOUND, UNKNOWN, AMBIGUOUS }

    public record SeatObservation(ObservationState state, String value) { }

    public record AnalysisInput(boolean succeeded, List<SeatObservation> seats) {
        public static AnalysisInput succeeded(List<SeatObservation> seats) {
            return new AnalysisInput(true, seats);
        }

        public static AnalysisInput failed() {
            return new AnalysisInput(false, List.of());
        }
    }

    public record Result(Status status, Reason reason) { }

    public static Result decide(AnalysisInput analysis, boolean performanceLinkCurrent,
            boolean completeReservationSnapshot, int candidateCount) {
        if (analysis == null || !analysis.succeeded()) return result(Status.ANALYSIS_FAILED, Reason.ANALYSIS_NOT_SUCCEEDED);
        if (!performanceLinkCurrent) return result(Status.UNVERIFIABLE, Reason.PERFORMANCE_LINK_UNVERIFIED);
        Result seats = usableSeats(analysis.seats());
        if (seats != null) return seats;
        // A zero-row lookup is meaningful only after the source has declared a complete snapshot.
        if (!completeReservationSnapshot)
            return result(Status.UNVERIFIABLE, Reason.RESERVATION_SOURCE_INCOMPLETE);
        if (candidateCount < 0) return result(Status.LOOKUP_FAILED, Reason.RESERVATION_LOOKUP_FAILED);
        if (candidateCount == 0) return result(Status.NO_MATCH, Reason.NO_CANDIDATE_FOUND);
        if (candidateCount == 1) return result(Status.MATCH_CANDIDATE, Reason.MATCHED_SINGLE_CANDIDATE);
        return result(Status.AMBIGUOUS, Reason.MULTIPLE_CANDIDATES);
    }

    private static Result usableSeats(List<SeatObservation> seats) {
        if (seats == null || seats.size() != 3)
            return result(Status.UNVERIFIABLE, Reason.SEAT_OBSERVATION_UNAVAILABLE);
        for (SeatObservation seat : seats) {
            if (seat != null && seat.state() == ObservationState.AMBIGUOUS)
                return result(Status.UNVERIFIABLE, Reason.SEAT_OBSERVATION_AMBIGUOUS);
            if (seat == null || seat.state() == null || seat.state() == ObservationState.UNKNOWN
                    || seat.value() == null || seat.value().isBlank())
                return result(Status.UNVERIFIABLE, Reason.SEAT_OBSERVATION_UNAVAILABLE);
        }
        return null;
    }

    private static Result result(Status status, Reason reason) {
        return new Result(status, reason);
    }
}
