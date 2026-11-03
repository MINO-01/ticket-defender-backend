# 관리자 팬 제보 원본 이미지 열람 API

계약: `admin-evidence-content-v1.yaml`

```http
GET /api/v1/admin/fan-reports/{fanReportId}/evidence/{evidenceId}/content
Authorization: Bearer {관리자 JWT}
```

## 방식 선택

- Backend가 원본을 읽어 직접 응답하는 프록시 방식을 사용합니다.
- S3 사전 서명 URL은 쓰지 않습니다. URL을 가진 사람은 유효기간 동안 권한 확인 없이 열 수 있어 권한 회수와 열람 기록을 서버에서 보장하기 어렵습니다.
- 응답에는 저장소 주소, 버킷, 객체 키, 객체 버전, 저장소 예외 내용을 넣지 않습니다.

## 처리 순서

1. 관리자 JWT 확인 (보안 필터)
2. 저장된 FanReport 조회 후 그 tenant/event로 권한과 역할(ANALYST, REVIEWER) 확인
3. evidenceId가 경로의 제보 소속인지 확인
4. 저장 상태(제보가 저장 대기나 저장 실패가 아니고 증거가 STORED), 보존 기한, 형식(PNG, JPEG), 설정 최대 크기 확인
5. 여기까지가 짧은 읽기 전용 트랜잭션입니다.
6. 트랜잭션 밖에서 동시 읽기 허가를 얻고, 서버에 저장된 객체 키와 객체 버전 또는 ETag로 `EvidenceObjectStorage.readVerifiedBytes()`를 호출합니다.
7. 읽은 크기, 저장소 Content-Type, 실제 바이트 형식, 실제 SHA-256을 저장값과 비교합니다. 비교 순서와 코드는 Track 2 Listing 분석과 같은 `EvidenceImageBytes` 규칙을 씁니다.
8. 별도 트랜잭션에서 권한과 저장 상태를 다시 확인하고, 객체 정보가 바뀌지 않았으면 성공 감사를 저장합니다.
9. 성공 감사가 커밋된 뒤에만 이미지 바이트를 씁니다. 응답을 다 쓴 뒤 허가를 돌려줍니다.

## 응답 헤더

- `Content-Type`: 검증한 `image/png` 또는 `image/jpeg`
- `Cache-Control: no-store`
- `X-Content-Type-Options: nosniff`
- `Content-Disposition: inline; filename="evidence-{evidenceId}.png"` (원본 파일명은 사용하지 않음)

이번 API는 관리자 미리보기용 `inline`만 제공합니다. 파일로 내려받는 `attachment` 정책, 워터마크, 다운로드 사유 입력은 정하지 않았습니다. 다운로드는 개인정보 반출 범위와 보존 정책 결정이 필요하므로 별도 Issue에서 정해야 합니다.

## 크기와 동시 요청 제한

| 설정 | 기본값 | 허용 범위 | 테스트에서 사용한 값 |
| --- | --- | --- | --- |
| `ticket-defender.evidence-content.max-bytes` | 20971520 (20 MiB) | 1 ~ 20971520 | 4096 (H2 테스트) |
| `ticket-defender.evidence-content.max-concurrent-reads` | 4 | 1 ~ 64 | 1 (H2 테스트), 2 (MinIO 테스트) |

- 설정이 범위를 벗어나면 애플리케이션이 시작되지 않습니다. 최대 크기는 팬 제보 접수 상한(20 MiB)을 넘을 수 없습니다.
- DB에 저장된 크기가 상한을 넘으면 원본을 읽지 않고 거절합니다. 저장소 크기 확인과 스트림 읽기 중에도 상한을 넘으면 중단합니다.
- 허가가 없으면 기다리지 않고 바로 `503 EVIDENCE_CONTENT_BUSY`로 거절하고 실패 감사를 남깁니다. 대기열은 만들지 않습니다.
- 메모리 추정: 기존 `readVerifiedBytes()`는 `ByteArrayOutputStream`에 모은 뒤 복사하므로, 20 MiB 원본 한 건은 읽는 동안 최대 약 52 MiB(버퍼 32 MiB와 복사본 20 MiB)를 잠시 쓰고, 이후 응답을 다 쓸 때까지 20 MiB를 유지합니다. 기본값 4에서는 최악의 경우 잠시 약 208 MiB가 필요합니다. 측정값이 아닌 계산값입니다.
- 기본값 4와 허용 범위는 운영 정책으로 확정한 값이 아닙니다. 운영 힙 크기와 실제 사용량을 보고 정해야 합니다. 애플리케이션 노드마다 따로 적용되며 여러 노드 전체 제한은 아닙니다.

## 감사 기록

새 테이블 `evidence_access_audit_events` (`docs/database/admin-evidence-content-audit-migration.sql`)에 저장합니다. 기존 `official_pdf_audit_events`는 발급 건에 묶여 있어 증거 열람에 쓸 수 없습니다.

- 저장 항목: 요청 ID, tenant, event, fanReportId, evidenceId, 관리자 멤버십 ID, 결과, 상세 코드, 시각
- 저장하지 않는 항목: 이미지 바이트, 객체 키, 객체 버전, JWT, 저장소 예외 내용
- 요청 ID가 기본키이므로 한 요청에는 성공이나 실패 중 하나만 남습니다.
- 성공 감사를 저장하지 못하면 이미지를 보내지 않고 `503 AUDIT_UNAVAILABLE`을 반환합니다.
- 실패 감사는 원래 요청과 분리된 트랜잭션으로 저장합니다. 실패 감사를 저장하지 못해도 원래 오류 코드는 그대로 반환하고 로그를 남깁니다. 기존 증거 보존 실패 감사와 같은 방식입니다.
- 403(역할 없음, `ACCESS_DENIED`)과 409(`TENANT_SELECTION_REQUIRED`)는 제보 고객사의 활성 멤버십과 공연 권한이 확인된 뒤에만 발생하므로, 그 멤버십으로 실패 감사를 남깁니다.
- 401(관리자 식별 불가)과 범위 밖 제보의 404(다른 고객사, 권한 없는 공연, 없는 제보)는 기록할 관리자 범위가 없으므로 이 테이블에 남기지 않습니다. 임의 관리자 ID를 만들지 않고 다른 고객사 범위에 기록하지도 않습니다. 이 요청들을 따로 남기는 보안 감사 경로는 현재 Backend에 없습니다.
- 권한이 확인된 제보에서 다른 제보의 evidenceId를 요청한 404는 evidenceId를 비운 채 실패 감사를 남깁니다.
- 성공 코드 `VERIFIED_FOR_RESPONSE`는 원본 검증을 마치고 응답을 보내기 시작했다는 뜻입니다. 클라이언트가 이미지를 다 받았다는 뜻이 아니며, 응답 도중 연결이 끊겨도 이 기록은 바뀌지 않습니다.

## 오류 코드

| 상태 | 코드 | 의미 |
| --- | --- | --- |
| 404 | RESOURCE_NOT_FOUND | 제보나 증거가 없거나 범위 밖이거나 다른 제보 소속 |
| 409 | TENANT_SELECTION_REQUIRED | 활성 고객사 멤버십이 둘 이상 (DEC-02 결정 전 차단) |
| 409 | SOURCE_CHANGED | 원본을 읽는 동안 저장된 객체 정보가 바뀜 |
| 422 | SOURCE_NOT_AVAILABLE | 저장 대기, 저장 실패, 증거 미저장 |
| 422 | RETENTION_EXPIRED | 제보나 증거의 보존 기한 만료 |
| 422 | UNSUPPORTED_IMAGE_TYPE | PNG, JPEG가 아닌 저장 형식 |
| 422 | EVIDENCE_CONTENT_TOO_LARGE | 설정 최대 크기 초과 |
| 422 | OBJECT_CHANGED | 객체 버전 또는 ETag, 저장소 Content-Type 변경 |
| 422 | OBJECT_MISSING | 버킷은 정상이나 객체 없음 |
| 422 | SIZE_MISMATCH, MEDIA_TYPE_MISMATCH, HASH_MISMATCH | 실제 크기, 실제 형식, 실제 SHA-256 불일치 |
| 503 | STORAGE_UNAVAILABLE | 저장소 장애 |
| 503 | EVIDENCE_CONTENT_BUSY | 동시 읽기 제한 초과 |
| 503 | AUDIT_UNAVAILABLE | 성공 감사 저장 실패 |

## 정하지 않은 사항

- 다운로드(`attachment`) 허용 여부와 조건
- 동시 읽기 수와 최대 크기의 운영값
- 원본 보존 기한과 개인정보 처리 적법성 (이번 구현으로 승인된 것으로 보지 않음)
- 다중 고객사 관리자 처리 정책 DEC-02
- 감사 기록 보관 기간과 조회 화면
