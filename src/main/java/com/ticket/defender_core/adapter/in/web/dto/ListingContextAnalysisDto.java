package com.ticket.defender_core.adapter.in.web.dto;

import com.ticket.defender_core.domain.ListingContextAnalysis;
import com.ticket.defender_core.domain.ListingContextObservation;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Listing Context 분석 이력 응답입니다. 객체 키와 저장소 객체 버전, 원본 이미지는 노출하지 않습니다. */
public final class ListingContextAnalysisDto {
    private ListingContextAnalysisDto() { }

    public record View(UUID analysisId, UUID fanReportId, UUID evidenceId, UUID requestId,
                       ListingContextAnalysis.Execution executionStatus,
                       ListingContextAnalysis.AnalysisStatus analysisStatus, String errorCode,
                       Integer analyzerHttpStatus, String analyzerErrorCode, Input input,
                       String modelName, String modelVersion, LocalDateTime analyzedAt, Boolean reviewRequired,
                       Map<String, Field> fields, CandidateMatch candidateMatch,
                       LocalDateTime createdAt, LocalDateTime completedAt) {
        public static View from(ListingContextAnalysis analysis, List<ListingContextObservation> observations) {
            Map<String, Field> fields = null;
            if (analysis.getExecutionStatus() == ListingContextAnalysis.Execution.SUCCEEDED) {
                Map<ListingContextObservation.Field, ListingContextObservation> byName = new LinkedHashMap<>();
                observations.forEach(item -> byName.put(item.getFieldName(), item));
                fields = new LinkedHashMap<>();
                for (ListingContextObservation.Field name : ListingContextObservation.Field.values()) {
                    ListingContextObservation item = byName.get(name);
                    if (item != null) fields.put(name.name(), Field.from(item));
                }
            }
            CandidateMatch match = analysis.getCandidateMatchStatus() == null ? null
                    : new CandidateMatch(analysis.getCandidateMatchStatus(), analysis.getCandidateMatchReason());
            return new View(analysis.getId(), analysis.getFanReportId(), analysis.getEvidenceId(),
                    analysis.getRequestId(), analysis.getExecutionStatus(), analysis.getAnalysisStatus(),
                    analysis.getErrorCode(), analysis.getAnalyzerHttpStatus(), analysis.getAnalyzerErrorCode(),
                    new Input(analysis.getExpectedSha256(), analysis.getVerifiedSha256(),
                            analysis.getExpectedByteSize(), analysis.getVerifiedByteSize(), analysis.getMediaType()),
                    analysis.getModelName(), analysis.getModelVersion(), analysis.getAnalyzedAt(),
                    analysis.getReviewRequired(), fields, match, analysis.getCreatedAt(), analysis.getCompletedAt());
        }
    }

    public record Input(String expectedSha256, String verifiedSha256, long expectedByteSize,
                        Long verifiedByteSize, String mediaType) { }

    /** value는 askingPrice일 때 Price이고, 그 외 FOUND 필드는 문자열입니다. */
    public record Field(ListingContextObservation.State state, Object value, String evidenceQuote,
                        ListingContextObservation.UnknownReason unknownReason, List<String> candidates) {
        static Field from(ListingContextObservation item) {
            Object value = item.getPriceRaw() != null ? new Price(item.getPriceRaw(), item.getPriceAmount())
                    : item.getValueText();
            return new Field(item.getState(), value, item.getEvidenceQuote(), item.getUnknownReason(),
                    item.candidates());
        }
    }

    public record Price(String raw, String amount) { }

    /** 기존 예매 후보 대조 연결 여부입니다. 현재 버전은 항상 NOT_LINKED와 그 사유를 반환합니다. */
    public record CandidateMatch(String status, String reason) { }

    public record Page(List<View> items, int page, int pageSize, boolean hasNext) { }
}
