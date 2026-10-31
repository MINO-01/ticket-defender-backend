package com.ticket.defender_core.adapter.out.api;

import com.ticket.defender_core.domain.ListingContextAnalysis;
import com.ticket.defender_core.domain.ListingContextObservation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Python Track 2 Listing Context API(POST /api/v1/parse/listing-context) 호출 어댑터입니다.
 * 내부 인증 비밀은 Track 1 설정과 분리하며, 비밀과 이미지와 응답 원문을 로그나 예외 메시지에 남기지 않습니다.
 */
@Component
public class ListingContextClient {
    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    public static final long MAX_REQUEST_BYTES = 7L * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final String PATH = "/api/v1/parse/listing-context";
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern AMOUNT = Pattern.compile("(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,2})?");
    private static final Set<String> TOP_LEVEL = Set.of("requestId", "evidenceId", "imageSha256", "status",
            "fields", "modelName", "modelVersion", "analyzedAt", "reviewRequired", "errorCode");
    private static final Set<String> OBSERVATION = Set.of("state", "value", "evidenceQuote", "unknownReason",
            "candidates");

    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final boolean enabled;
    private final String baseUrl;
    private final String internalKey;
    private final Duration readTimeout;
    private final HttpClient http;

    public ListingContextClient(
            @Value("${ticket-defender.track2-listing-analysis.enabled:false}") boolean enabled,
            @Value("${ticket-defender.track2-listing-analysis.base-url:}") String baseUrl,
            @Value("${ticket-defender.track2-listing-analysis.internal-key:}") String internalKey,
            @Value("${ticket-defender.track2-listing-analysis.connect-timeout:PT3S}") Duration connectTimeout,
            @Value("${ticket-defender.track2-listing-analysis.read-timeout:PT60S}") Duration readTimeout) {
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.internalKey = internalKey == null ? "" : internalKey;
        this.readTimeout = readTimeout;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(connectTimeout).build();
    }

    /** 기능 활성화, 내부 비밀, 전송 경로가 모두 안전하게 설정된 경우에만 true입니다. */
    public boolean configured() {
        if (!enabled || internalKey.isBlank() || internalKey.length() < 32 || baseUrl.isBlank()) return false;
        try {
            URI uri = URI.create(baseUrl);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null) return false;
            if ("https".equals(uri.getScheme())) return true;
            return "http".equals(uri.getScheme())
                    && ("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost()));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public Duration readTimeout() {
        return readTimeout;
    }

    /** 검증된 원본 바이트만 전달합니다. 실패는 정상 결과로 바꾸지 않고 Failure로 반환합니다. */
    public Result analyze(UUID requestId, UUID evidenceId, String imageSha256, String mimeType, byte[] image) {
        if (!configured()) return new Failure("ANALYZER_DISABLED", null, null);
        if (image.length > MAX_IMAGE_BYTES) return new Failure("ANALYSIS_INPUT_TOO_LARGE", null, null);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("requestId", requestId.toString());
        body.put("evidenceId", evidenceId.toString());
        body.put("imageSha256", imageSha256);
        body.put("mimeType", mimeType);
        body.put("imageBase64", Base64.getEncoder().encodeToString(image));
        byte[] payload = json.writeValueAsBytes(body);
        if (payload.length > MAX_REQUEST_BYTES) return new Failure("ANALYSIS_INPUT_TOO_LARGE", null, null);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + PATH))
                .timeout(readTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, application/problem+json")
                .header("X-TD-Internal-Key", internalKey)
                .header("X-Request-Id", requestId.toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        HttpResponse<InputStream> response;
        byte[] raw;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                raw = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
        } catch (HttpConnectTimeoutException | ConnectException unreachable) {
            return new Failure("ANALYZER_CONNECTION_FAILED", null, null);
        } catch (HttpTimeoutException timeout) {
            return new Failure("ANALYZER_TIMEOUT", null, null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Failure("ANALYZER_CONNECTION_FAILED", null, null);
        } catch (IOException | RuntimeException transport) {
            return new Failure("ANALYZER_CONNECTION_FAILED", null, null);
        }
        int status = response.statusCode();
        if (raw.length > MAX_RESPONSE_BYTES) return new Failure("ANALYZER_RESPONSE_INVALID", status, null);
        if (status != 200) return error(status, raw, requestId);
        try {
            return new Success(parse(json.readTree(raw), requestId, evidenceId, imageSha256), sha256(raw));
        } catch (IdentityMismatch mismatch) {
            return new Failure("ANALYZER_IDENTITY_MISMATCH", status, null);
        } catch (RuntimeException invalid) {
            return new Failure("ANALYZER_RESPONSE_INVALID", status, null);
        }
    }

    private Failure error(int status, byte[] raw, UUID requestId) {
        String code = null;
        try {
            JsonNode problem = json.readTree(raw);
            JsonNode value = problem.get("code");
            JsonNode traced = problem.get("requestId");
            JsonNode analysis = problem.get("analysisStatus");
            if (value != null && value.isString() && CODE.matcher(value.stringValue()).matches()
                    && analysis != null && "FAILED".equals(analysis.stringValue())
                    && (traced == null || traced.isNull() || requestId.toString().equals(traced.stringValue())))
                code = value.stringValue();
        } catch (RuntimeException unreadable) {
            code = null;
        }
        String mapped = switch (status) {
            case 401 -> "ANALYZER_AUTH_FAILED";
            case 400, 413, 415, 422 -> "ANALYZER_INPUT_REJECTED";
            case 502 -> "ANALYZER_MODEL_FAILED";
            case 503 -> "ANALYZER_UNAVAILABLE";
            case 504 -> "ANALYZER_MODEL_TIMEOUT";
            default -> status >= 200 && status < 300 ? "ANALYZER_RESPONSE_INVALID" : "ANALYZER_INTERNAL_ERROR";
        };
        return new Failure(mapped, status, code);
    }

    private Parsed parse(JsonNode root, UUID requestId, UUID evidenceId, String imageSha256) {
        exactKeys(root, TOP_LEVEL);
        if (!requestId.toString().equals(text(root, "requestId"))
                || !evidenceId.toString().equals(text(root, "evidenceId"))
                || !imageSha256.equals(text(root, "imageSha256")))
            throw new IdentityMismatch();
        ListingContextAnalysis.AnalysisStatus status = ListingContextAnalysis.AnalysisStatus.valueOf(text(root, "status"));
        if (status == ListingContextAnalysis.AnalysisStatus.FAILED) throw new IllegalArgumentException();
        String modelName = bounded(text(root, "modelName"), 1, 128);
        JsonNode version = root.get("modelVersion");
        String modelVersion = version.isNull() ? null : bounded(stringOf(version), 0, 128);
        Instant analyzedAt = instant(text(root, "analyzedAt"));
        if (!root.get("reviewRequired").isBoolean() || !root.get("reviewRequired").booleanValue()
                || !root.get("errorCode").isNull()) throw new IllegalArgumentException();
        JsonNode fields = root.get("fields");
        Set<String> names = new java.util.HashSet<>();
        for (ListingContextObservation.Field field : ListingContextObservation.Field.values()) names.add(field.name());
        exactKeys(fields, names);
        List<Observation> observations = new ArrayList<>();
        for (ListingContextObservation.Field field : ListingContextObservation.Field.values())
            observations.add(observation(field, fields.get(field.name())));
        if (derive(observations) != status) throw new IllegalArgumentException();
        return new Parsed(status, modelName, modelVersion, analyzedAt, List.copyOf(observations));
    }

    private Observation observation(ListingContextObservation.Field field, JsonNode node) {
        exactKeys(node, OBSERVATION);
        ListingContextObservation.State state = ListingContextObservation.State.valueOf(text(node, "state"));
        JsonNode value = node.get("value");
        JsonNode quoteNode = node.get("evidenceQuote");
        String quote = quoteNode.isNull() ? null : bounded(stringOf(quoteNode), 1, 512);
        JsonNode reasonNode = node.get("unknownReason");
        ListingContextObservation.UnknownReason reason = reasonNode.isNull() ? null
                : ListingContextObservation.UnknownReason.valueOf(stringOf(reasonNode));
        JsonNode candidateNode = node.get("candidates");
        if (!candidateNode.isArray() || candidateNode.size() > 3) throw new IllegalArgumentException();
        List<String> candidates = new ArrayList<>();
        for (JsonNode candidate : candidateNode) {
            String item = bounded(stringOf(candidate), 1, 128);
            if (item.isBlank() || candidates.contains(item)) throw new IllegalArgumentException();
            candidates.add(item);
        }
        String text = null;
        String priceRaw = null;
        String priceAmount = null;
        switch (state) {
            case FOUND -> {
                if (quote == null || reason != null || !candidates.isEmpty() || value.isNull())
                    throw new IllegalArgumentException();
                if (field == ListingContextObservation.Field.askingPrice) {
                    exactKeys(value, Set.of("raw", "amount"));
                    priceRaw = bounded(text(value, "raw"), 1, 64);
                    priceAmount = text(value, "amount");
                    if (!AMOUNT.matcher(priceAmount).matches() || !quote.contains(priceRaw))
                        throw new IllegalArgumentException();
                } else {
                    text = bounded(stringOf(value), 1, 256);
                    if (text.isBlank()) throw new IllegalArgumentException();
                }
            }
            case UNKNOWN -> {
                if (!value.isNull() || quote != null || reason == null || !candidates.isEmpty())
                    throw new IllegalArgumentException();
            }
            case AMBIGUOUS -> {
                if (!value.isNull() || quote == null || reason != null || candidates.size() < 2)
                    throw new IllegalArgumentException();
            }
        }
        return new Observation(field, state, text, priceRaw, priceAmount, quote, reason, List.copyOf(candidates));
    }

    /** Python derive_listing_status와 같은 규칙으로 응답 상태가 필드와 모순되지 않는지 확인합니다. */
    private static ListingContextAnalysis.AnalysisStatus derive(List<Observation> observations) {
        Map<ListingContextObservation.Field, Boolean> found = new LinkedHashMap<>();
        observations.forEach(item -> found.put(item.field(), item.state() == ListingContextObservation.State.FOUND));
        if (!found.containsValue(true)) return ListingContextAnalysis.AnalysisStatus.UNVERIFIABLE;
        if (found.get(ListingContextObservation.Field.saleText)
                && (found.get(ListingContextObservation.Field.eventName)
                || found.get(ListingContextObservation.Field.platformDisplayName)
                || found.get(ListingContextObservation.Field.askingPrice)))
            return ListingContextAnalysis.AnalysisStatus.COMPLETED;
        return ListingContextAnalysis.AnalysisStatus.PARTIAL;
    }

    private static void exactKeys(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) throw new IllegalArgumentException();
        for (Map.Entry<String, JsonNode> entry : node.properties())
            if (!expected.contains(entry.getKey())) throw new IllegalArgumentException();
    }

    private static String text(JsonNode node, String name) {
        return stringOf(node.get(name));
    }

    private static String stringOf(JsonNode node) {
        if (node == null || !node.isString()) throw new IllegalArgumentException();
        return node.stringValue();
    }

    private static String bounded(String value, int min, int max) {
        int length = value.codePointCount(0, value.length());
        if (length < min || length > max) throw new IllegalArgumentException();
        return value;
    }

    private static Instant instant(String value) {
        try {
            return java.time.OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException(invalid);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class IdentityMismatch extends RuntimeException {
        private IdentityMismatch() {
            super(null, null, false, false);
        }
    }

    public sealed interface Result permits Success, Failure { }
    public record Success(Parsed parsed, String responseSha256) implements Result { }
    /** errorCode는 Spring 분류 코드이고 analyzerErrorCode는 Python problem+json의 code입니다. */
    public record Failure(String errorCode, Integer httpStatus, String analyzerErrorCode) implements Result { }
    public record Parsed(ListingContextAnalysis.AnalysisStatus status, String modelName, String modelVersion,
                         Instant analyzedAt, List<Observation> observations) { }
    public record Observation(ListingContextObservation.Field field, ListingContextObservation.State state,
                              String value, String priceRaw, String priceAmount, String evidenceQuote,
                              ListingContextObservation.UnknownReason unknownReason, List<String> candidates) { }
}
