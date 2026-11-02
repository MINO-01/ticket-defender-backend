package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.storage.S3EvidenceObjectStorage;
import com.ticket.defender_core.security.TenantSelectionRequiredException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 관리자가 팬 제보의 비공개 원본 이미지를 열람합니다.
 * 권한과 저장 상태를 확인한 뒤 서버 소유 키로 원본을 읽고, 크기와 형식과 SHA-256을 검증하고, 성공 감사를 저장한 다음에만 바이트를 돌려줍니다.
 * 성공 감사(VERIFIED_FOR_RESPONSE)는 검증을 마치고 응답을 보내기 시작했다는 뜻이며 클라이언트 수신 완료를 뜻하지 않습니다.
 * 원본 열람은 조사 지원용이며 판정, Case 생성, 제재, PDF 발급을 하지 않습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminEvidenceContentService {
    private final EvidenceContentAccessWriteService writer;
    private final EvidenceObjectStorage storage;
    private final EvidenceContentReadLimiter limiter;

    /** 검증과 성공 감사가 끝난 원본입니다. 응답을 다 쓴 뒤 close해서 동시 읽기 허가를 돌려줘야 합니다. */
    public static final class Content implements AutoCloseable {
        private final byte[] bytes;
        private final String mediaType;
        private final UUID evidenceId;
        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Content(byte[] bytes, String mediaType, UUID evidenceId, Runnable release) {
            this.bytes = bytes;
            this.mediaType = mediaType;
            this.evidenceId = evidenceId;
            this.release = release;
        }

        public byte[] bytes() {
            return bytes;
        }

        public String mediaType() {
            return mediaType;
        }

        /** 응답 파일명은 원본 파일명이 아니라 증거 ID와 검증된 형식으로만 만듭니다. */
        public String filename() {
            return "evidence-" + evidenceId + ("image/png".equals(mediaType) ? ".png" : ".jpg");
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) release.run();
        }
    }

    public Content read(UUID fanReportId, UUID evidenceId) {
        UUID requestId = UUID.randomUUID();
        // 401과 범위 밖 404는 기록할 관리자 범위가 없으므로 감사하지 않습니다.
        // 제보 고객사 멤버십과 공연 권한이 확인된 뒤의 거부(403 역할 부족, 409 다중 고객사)는 실패 감사를 남깁니다.
        EvidenceContentAccessWriteService.Checked checked;
        try {
            checked = writer.authorize(fanReportId, evidenceId);
        } catch (AccessDeniedException | TenantSelectionRequiredException denied) {
            auditDenied(requestId, fanReportId, evidenceId, denied);
            throw denied;
        }
        boolean acquired = false;
        try {
            EvidenceContentAccessWriteService.Source source = checked.source();
            if (source == null) throw new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
            String rejected = EvidenceContentAccessWriteService.precheck(source, now(), limiter.maxBytes());
            if (rejected != null) throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, rejected);
            if (!limiter.tryAcquire())
                throw new CaseApiException(HttpStatus.SERVICE_UNAVAILABLE, "EVIDENCE_CONTENT_BUSY");
            acquired = true;
            byte[] bytes = verifiedBytes(source);
            try {
                writer.succeed(requestId, checked, limiter.maxBytes(), now());
            } catch (CaseApiException | ResponseStatusException | AccessDeniedException
                     | TenantSelectionRequiredException recheckFailed) {
                throw recheckFailed;
            } catch (RuntimeException auditFailure) {
                CaseApiException blocked = new CaseApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUDIT_UNAVAILABLE");
                blocked.addSuppressed(auditFailure);
                throw blocked;
            }
            Content content = new Content(bytes, source.mediaType(), source.evidenceId(), limiter::release);
            acquired = false;
            return content;
        } catch (RuntimeException failure) {
            if (acquired) limiter.release();
            auditFailure(requestId, checked, failure);
            throw failure;
        }
    }

    /** 7~8단계. 트랜잭션 밖에서 서버 소유 키와 저장된 객체 버전 또는 ETag로 원본을 읽고 저장값과 비교합니다. */
    private byte[] verifiedBytes(EvidenceContentAccessWriteService.Source source) {
        EvidenceObjectStorage.VerifiedBytes original;
        try {
            original = storage.readVerifiedBytes(source.objectKey(), source.objectVersion(), limiter.maxBytes());
        } catch (RuntimeException failure) {
            String code = storageFailure(failure);
            throw new CaseApiException("STORAGE_UNAVAILABLE".equals(code)
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNPROCESSABLE_ENTITY, code);
        }
        EvidenceObjectStorage.ByteVerification verified = original.verification();
        String rejected = null;
        // 판정 순서와 코드는 Listing 분석의 원본 검증과 같습니다.
        if (verified.byteSize() != source.byteSize() || original.bytes().length != verified.byteSize())
            rejected = "SIZE_MISMATCH";
        else if (!Objects.equals(verified.mediaType(), source.mediaType())) rejected = "OBJECT_CHANGED";
        else if (!source.mediaType().equals(EvidenceImageBytes.sniff(original.bytes())))
            rejected = "MEDIA_TYPE_MISMATCH";
        else if (!EvidenceImageBytes.sameDigest(verified.sha256(), source.sha256())
                || !EvidenceImageBytes.sameDigest(EvidenceImageBytes.sha256(original.bytes()), source.sha256()))
            rejected = "HASH_MISMATCH";
        if (rejected != null) throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, rejected);
        return original.bytes();
    }

    /** 저장소 예외 내용과 주소는 응답과 감사에 남기지 않고 분류 코드만 사용합니다. */
    private String storageFailure(RuntimeException failure) {
        if (failure instanceof S3EvidenceObjectStorage.ObjectMarkerChangedException) return "OBJECT_CHANGED";
        if (failure instanceof S3EvidenceObjectStorage.ObjectSizeExceededException)
            return "EVIDENCE_CONTENT_TOO_LARGE";
        if (failure instanceof S3Exception error) {
            if (error.statusCode() == 412) return "OBJECT_CHANGED";
            if (error.statusCode() == 404) {
                try {
                    storage.checkReady();
                    return "OBJECT_MISSING";
                } catch (RuntimeException unavailable) {
                    return "STORAGE_UNAVAILABLE";
                }
            }
        }
        return "STORAGE_UNAVAILABLE";
    }

    /** 실패 감사를 저장하지 못해도 원래 오류 분류는 바꾸지 않습니다. 기존 증거 보존 실패 감사와 같은 방식입니다. */
    private void auditFailure(UUID requestId, EvidenceContentAccessWriteService.Checked checked,
            RuntimeException failure) {
        String code = failureCode(failure);
        try {
            writer.fail(requestId, checked, code, now());
        } catch (RuntimeException auditFailure) {
            failure.addSuppressed(auditFailure);
            log.error("원본 열람 실패 감사를 저장하지 못했습니다. 오류 코드: {}, 감사 실패 유형: {}",
                    code, auditFailure.getClass().getSimpleName());
        }
    }

    private void auditDenied(UUID requestId, UUID fanReportId, UUID evidenceId, RuntimeException denied) {
        String code = failureCode(denied);
        try {
            if (!writer.denied(requestId, fanReportId, evidenceId, code, now()))
                log.warn("원본 열람 거부를 감사할 관리자 범위를 다시 확인하지 못했습니다. 오류 코드: {}", code);
        } catch (RuntimeException auditFailure) {
            denied.addSuppressed(auditFailure);
            log.error("원본 열람 거부 감사를 저장하지 못했습니다. 오류 코드: {}, 감사 실패 유형: {}",
                    code, auditFailure.getClass().getSimpleName());
        }
    }

    private static String failureCode(RuntimeException failure) {
        if (failure instanceof CaseApiException known) return known.code();
        if (failure instanceof TenantSelectionRequiredException) return "TENANT_SELECTION_REQUIRED";
        if (failure instanceof AccessDeniedException) return "ACCESS_DENIED";
        if (failure instanceof ResponseStatusException) return "RESOURCE_NOT_FOUND";
        return "INTERNAL_ERROR";
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
