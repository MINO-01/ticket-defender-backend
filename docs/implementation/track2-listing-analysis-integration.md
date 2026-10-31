# Track 2 Listing Context 분석 연동 (#50)

상태: IMPLEMENTED(합성 격리 환경 검증). 실제 팬 이미지의 외부 모델 전송은 정책 승인 전까지 차단합니다.

## 경계

- Spring은 관리자 인증과 tenant/event 권한, 제보와 증거의 소속, 비공개 원본 읽기, SHA-256 검증, 결과 저장을 담당합니다.
- Python(`ticket-defender-data` main `7e11c65`, `docs/api/track2-listing-context-v1.md`)은 전달된 바이트의 형식과 digest를 다시 확인하고 관찰 결과만 반환합니다. Python 계약은 변경하지 않았습니다.
- 기존 `/api/v1/parse/ticket-image`와 `VlmAnalysisService`는 변경하지 않았습니다.
- 분석 결과는 조사 후보입니다. Case 확정, 예매 취소, 제재, 공식 PDF 발급, `MATCH_CANDIDATE` 생성은 하지 않습니다.

## API

`POST /api/v1/admin/fan-reports/{fanReportId}/evidence/{evidenceId}/listing-analyses` (헤더 `Idempotency-Key`, 본문 없음)
`GET  /api/v1/admin/fan-reports/{fanReportId}/evidence/{evidenceId}/listing-analyses?page=0&pageSize=20`

역할은 ANALYST 또는 REVIEWER입니다. 상세 상태 코드는 `docs/api/track2-listing-analysis-v1.yaml`을 따릅니다.

## 처리 순서

1. 저장된 FanReport의 tenant/event로 `AdminAccessService.requireResourceAccess`를 호출합니다. 범위 밖 자원은 404입니다.
2. EvidenceAsset이 경로의 제보 소속인지 확인합니다. 다른 제보의 증거도 404입니다(서비스와 쓰기 트랜잭션 양쪽에서 확인).
3. 분석 설정이 없거나 안전하지 않으면 이력 없이 503 `ANALYZER_DISABLED`입니다.
4. 짧은 트랜잭션에서 시도 행을 만듭니다. 사전 검사(접수 상태, STORED와 객체 버전, 보존기간, JPEG/PNG, 5 MiB 이하) 실패는 Python 호출 없이 `INPUT_REJECTED`로 종료합니다.
5. 트랜잭션 밖에서 DB의 서버 소유 객체 키와 객체 버전으로 `EvidenceObjectStorage.readVerifiedBytes`를 호출합니다. W06의 `verifyBytes`와 같은 버전 고정, ETag 조건, 크기 제한 경로를 공유하며 바이트만 추가로 반환합니다.
6. 크기, 저장 Content-Type, 매직 바이트 형식, 실제 SHA-256을 확인합니다.
7. 모든 검사를 통과하면 `X-TD-Internal-Key`로 Python을 호출합니다. 요청은 `requestId`, `evidenceId`, `imageSha256`, `mimeType`, `imageBase64` 5개 필드이며 전체 본문 7 MiB 이하만 전송합니다.
8. 응답을 계약대로 엄격히 검증한 뒤 짧은 트랜잭션에서 결과를 저장하고 활성 슬롯을 해제합니다.

## 응답 검증

- 최상위 10개 키, 9개 필드, 필드별 5개 키 외의 속성이나 중복 키는 거부합니다.
- `requestId`, `evidenceId`, `imageSha256` 불일치는 `ANALYZER_IDENTITY_MISMATCH`입니다.
- 200의 `status`는 COMPLETED, PARTIAL, UNVERIFIABLE만 허용하고 Python `derive_listing_status`와 같은 규칙으로 필드와 모순되지 않는지 확인합니다.
- FOUND, UNKNOWN, AMBIGUOUS 불변식과 길이 제한(값 256, 근거 문구 512, 후보 3개 각 128, 가격 raw 64, amount 정규식), `reviewRequired: true`, `errorCode: null`을 확인합니다.
- 위반은 `ANALYZER_RESPONSE_INVALID`로 FAILED 저장하며 관찰 결과는 저장하지 않습니다.
- 오류 응답은 Spring 분류 코드(`errorCode`)와 요청 ID가 일치하는 Python `code`(`analyzerErrorCode`)를 분리해 저장합니다.
- 연결 실패(`ANALYZER_CONNECTION_FAILED`), Spring 응답 대기 초과(`ANALYZER_TIMEOUT`), Python 모델 시간 초과(`ANALYZER_MODEL_TIMEOUT`)를 구분합니다.

## 저장 구조 (`docs/database/track2-listing-analysis-migration.sql`)

- `listing_context_analyses`: 시도 1건. tenant/event/제보/증거, 요청자, 요청 ID, 객체 키 SHA-256, 객체 버전, 기대값과 검증된 SHA-256 및 크기, 실행 상태, 분석 상태, 오류 코드, 모델 이름과 버전, 분석 시각, 응답 SHA-256, 후보 대조 연결 사유, 임대 만료 시각, `row_version`.
- `listing_context_observations`: 필드 관찰 9건. 상태, 값 또는 가격 raw와 amount, 근거 문구, UNKNOWN 사유, AMBIGUOUS 후보 최대 3개.
- 원본 이미지와 Base64, 객체 키 원문, 모델 응답 원문은 저장하지 않습니다.
- 복합 FK로 tenant/event/제보, 제보/증거, tenant/요청자 소속을 강제하고 CHECK로 상태 조합과 관찰 불변식을 강제합니다.

## 중복 요청, 동시 실행, 장애 복구

- UNIQUE(요청자, 증거, Idempotency-Key 해시): 같은 키는 Python을 다시 호출하지 않고 기존 결과를 재현합니다.
- UNIQUE(`active_evidence_id`): IN_PROGRESS 동안만 값이 있어 증거별 동시 실행을 1건으로 제한합니다. 경합한 요청은 409 `ANALYSIS_IN_PROGRESS`입니다.
- 실패 후 재시도는 새 키로 새 행을 만들며 이전 이력은 바꾸지 않습니다.
- 프로세스 중단으로 남은 IN_PROGRESS는 임대 만료(기본 5분, 최소 응답 대기 시간 + 3분) 후 다음 요청이 `ABANDONED`로 닫고 슬롯을 해제합니다. 닫힌 시도에 늦게 도착한 결과는 기록하지 않습니다.
- 외부 호출은 DB 트랜잭션 밖에서 수행합니다. 메시지 브로커는 도입하지 않았습니다.

## 기존 후보 대조와의 관계

기존 `VlmAnalysisService`는 검증된 `performanceRef`, 좌석 3요소, 0.85 이상의 `mappingScore`가 있어야 후보를 조회합니다. Listing Context 응답에는 `mappingScore`가 없고 추출 신뢰도를 예매 일치 신뢰도로 쓸 수 없으므로 연결하지 않았습니다. 각 결과에 `candidate_match_status = NOT_LINKED`와 사유를 저장합니다.

| 사유 | 의미 |
| --- | --- |
| `PERFORMANCE_REF_UNVERIFIED` | 제보에 검증된 회차 식별자가 없습니다. 현재 앱에는 이 값을 설정하는 경로가 없습니다. |
| `SEAT_NOT_FOUND` | zone, row, seat 중 FOUND가 아닌 항목이 있습니다. |
| `MATCH_SCORE_UNAVAILABLE` | 회차와 좌석이 있어도 일치 신뢰도가 계약에 없습니다. |
| `ANALYSIS_NOT_SUCCEEDED` | 분석이 실패했습니다. |

## 설정

| 속성 | 환경 변수 | 기본값 |
| --- | --- | --- |
| `ticket-defender.track2-listing-analysis.enabled` | `TRACK2_LISTING_ANALYSIS_ENABLED` | `false` |
| `...base-url` | `TRACK2_LISTING_ANALYSIS_URL` | 없음. loopback 외에는 HTTPS만 허용 |
| `...internal-key` | `TRACK2_LISTING_ANALYSIS_INTERNAL_KEY` | 없음. Track 1 설정과 분리, 32자 미만이면 차단 |
| `...connect-timeout` / `...read-timeout` | 동일 접두사 | `PT3S` / `PT60S` |
| `...attempt-lease` | `TRACK2_LISTING_ANALYSIS_ATTEMPT_LEASE` | `PT5M` |

원본 읽기에는 기존 `FAN_REPORT_S3_HEAD_*` 읽기 자격 증명이 필요합니다. 로그에는 분석 ID, 요청 ID, 실행 상태, 오류 코드만 남깁니다.

## 미구현 및 정책 대기

- 실제 팬 이미지의 외부 모델 전송, 미성년자 제보, 국외 이전, 보존 정책은 승인되지 않았습니다. 기본값은 비활성입니다.
- 기존 예매 후보 대조 연결은 검증된 회차 식별자 설정 경로와 예매 일치 신뢰도 정의가 필요합니다.
- `FanReport.intakeStatus`는 분석 결과로 변경하지 않습니다. ANALYSIS_PENDING 등 접수 상태 전이 정책은 미정입니다.
- 임대 만료 IN_PROGRESS를 닫는 주기 작업은 없으며 다음 요청 시 정리합니다.
- 관리자 화면 연동은 Frontend 작업입니다.
