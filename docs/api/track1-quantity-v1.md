# Track 1 구매 수량 원장과 v2 분석 API (Backend #81)

상태: **IMPLEMENTED**. 실제 코드와 통합 테스트 기준이며 OpenAPI는 `track1-quantity-v1.yaml`, 합성 예시는 `fixtures/track1-quantity/`에 있습니다.

## DEMO_V1 호환 확장

`GET /api/v1/admin/events/{eventId}/track1-quantity/analysis-runs/{runId}`의 실행 상세에는
`sharedSignalObservations`와 `sharedSignalObservationCount`가 추가된다. 관찰값은 해당 실행에 고정 저장되며
원본 IP·주소·HMAC은 반환하지 않는다. 목록은 화면 표시용 건수만 반환하고 관찰 상세 배열은 항상 빈 배열이다.

각 관찰값은 `observationId`, `scopeType`, `scopeId`, `signalType` (`IP`/`ADDRESS`),
`distinctAccountCount`, `scopeAccountCount`, `distinctReservationCount`, `totalTicketQuantity`,
`ticketQuantityStatus` (`VERIFIED`/`UNAVAILABLE`), `corroboration.paymentSharedAccountCount`,
`corroboration.deviceSharedAccountCount`, `ipNetworkType`, `purchaseWindowSeconds`,
`excludedFromLouvain`을 가진다. `VERIFIED`일 때만 `totalTicketQuantity`는 0 이상의 정수이고,
`UNAVAILABLE`일 때는 `null`이다. `purchaseWindowSeconds`와 `ipNetworkType`은 `null`일 수 있다.

공통: 관리자 JWT 필수, `tenantId`는 요청으로 받지 않습니다. 범위 밖 자원은 `404 RESOURCE_NOT_FOUND`, 역할 부족은 `403`, 오류는 `application/problem+json`의 `code`입니다. 응답에 원문 참조와 저장 HMAC은 없습니다.

운영 게이트: `TRACK1_V2_ENABLED` 기본값은 `false`이며 꺼져 있으면 실행 요청은 `503 TRACK1_V2_DISABLED`입니다. 반입과 실행은 기존 합성 격리 게이트(`SYNTHETIC_ISOLATED`, 격리 DB, 등록 tenant)를 통과해야 합니다. 실제 고객사 데이터 반입은 활성화되어 있지 않습니다.

## Endpoint

| Method | 경로 (`/api/v1/admin/events/{eventId}/track1-quantity`) | 역할 | 성공 |
|---|---|---|---|
| POST | `/ledger-imports` + `Idempotency-Key` | TENANT_ADMIN | 201 `LedgerBatch` |
| GET | `/ledger-imports?page&pageSize` | TENANT_ADMIN, ANALYST, REVIEWER, APPROVER | 200 페이지 |
| POST | `/analysis-runs` + `Idempotency-Key` | TENANT_ADMIN, ANALYST | 202 `AnalysisRun` |
| GET | `/analysis-runs?page&pageSize` | 조회 역할 | 200 페이지 |
| GET | `/analysis-runs/{runId}` | 조회 역할 | 200 `AnalysisRun` |

제한: 1,000행 초과 또는 필수 상위 값 누락은 `422 INVALID_REQUEST`, 본문 2MiB 초과나 길이 미상은 `413 LEDGER_IMPORT_BODY_TOO_LARGE`입니다.

## 상태 의미

- `executionStatus`: 현재 호출은 완료까지 동기로 처리하므로 POST 응답은 `SUCCEEDED` 또는 `FAILED`입니다. `RUNNING`은 다른 요청이 실행 중일 때 목록에서 보입니다. `QUEUED`는 반환하지 않습니다.
- `SUCCEEDED`면 `evaluation`이 있습니다. `evaluation.status`가 `EVALUATED`이고 `findings`가 비어 있을 때만 "한도 초과 신호 0건"입니다.
- 평가 불가: `POLICY_NOT_CONFIRMED`, `QUANTITY_DATA_INCOMPLETE`, `POLICY_TIME_NOT_APPLICABLE`. `evaluation.reasonCode`로 사유를 구분합니다: `NO_CONFIRMED_ACCOUNT_POLICY`, `EVENT_SCOPE_AGGREGATION_NOT_SUPPORTED`, `GROSS_RULE_NOT_SUPPORTED`, `INCOMPLETE_SNAPSHOT`, `UNKNOWN_TICKET_STATUS`.
- `FAILED`면 `evaluation`은 `null`이고 `failureCode`가 있습니다: `ANALYZER_TIMEOUT`, `ANALYZER_UNREACHABLE`, `ANALYZER_HTTP_5XX`, `ANALYZER_HTTP_4XX`, `ANALYZER_UNAVAILABLE`, `ANALYZER_CONTRACT_VIOLATION`, `ANALYZER_AUTH_CONFIGURATION`, `ANALYSIS_FAILED`, `RESULT_PERSISTENCE_FAILED`, `ANALYSIS_INTERRUPTED`, `LEDGER_INTEGRITY_MISMATCH`, `NO_EFFECTIVE_TICKETS_AFTER_NETTING`. 재시도는 새 `Idempotency-Key`로 합니다.
- 실행 충돌: `409 TRACK1_QUANTITY_RUN_IN_PROGRESS`, 동시 생성 경합 `409 TRACK1_QUANTITY_RUN_CONCURRENT`. 키 재사용 `409 IDEMPOTENCY_KEY_REUSED`, 다른 내용의 같은 `sourceVersion`은 `409 LEDGER_SOURCE_VERSION_CONFLICT`.

구매 한도 초과와 `riskScore`는 조사 신호와 조사 우선순위입니다. 불법 확정이 아니며 Case, EvidenceSnapshot, ReportVersion을 자동 변경하지 않습니다.

## 수량 규칙

- `TICKET` 단위는 수량 1, `ORDER_LINE`은 1 이상입니다. `ticketReference`는 배치 안에서 유일하고 한 예매는 한 계정에만 속합니다.
- 없는 구매시각, 수량, 상태는 만들지 않고 행 오류로 배치 전체를 `REJECTED` 처리합니다. 거절 배치는 이력에 남고 `sourceVersion`을 점유하지 않습니다.
- 정책이 `NETTED`이고 스냅샷이 완전하며 `UNKNOWN`이 없을 때만 `CANCELLED`, `REFUNDED` 행을 제외하고 평가합니다(`nettedExcludedRowCount`). `GROSS`는 Data 미지원으로 평가 불가입니다.
- `ACCOUNT_LIMIT_SNAPSHOT`은 Data v2 미지원이라 `REJECTED`(`ACCOUNT_SNAPSHOT_NOT_SUPPORTED_BY_DATA_V2`)로 남습니다. PERSON 정책은 선택되지 않아 `POLICY_NOT_CONFIRMED`입니다.

## Frontend PROPOSED 문서와의 차이

| 항목 | 변경 |
|---|---|
| 반입 요청 | `completeSnapshot`(boolean) 필수 의미 추가. 없으면 `false`로 저장되어 `INCOMPLETE_SNAPSHOT` |
| 반입 행 | `paymentReference`, `addressReference`, `deviceReference`, `ipReference` 중 1개 이상 필수. Louvain 입력입니다 |
| `LedgerBatch` | `inputType`, `quantityBasis`, `completeSnapshot` 추가, `errors[].rowIndex`는 배치 오류면 `null` |
| 입력 오류 HTTP | `422 INVALID_REQUEST` |
| `AnalysisRun` | `clusters[{clusterId,riskScore,memberCount}]`, `nettedExcludedRowCount` 추가, `QUEUED` 없음 |
| `evaluation` | `reasonCode` 추가 |
| 토큰 | `accountToken`, `reservationToken`은 실행별 표시 토큰이므로 실행 간 비교에 쓰지 마십시오 |

## 함께 추가된 관리자 조회

| Method | 경로 | 역할 |
|---|---|---|
| GET | `/api/v1/admin/me` → `{tenantId, roles, eventIds}` | 인증된 관리자. 여러 고객사면 `409 TENANT_SELECTION_REQUIRED` |
| GET | `/api/v1/admin/events/{eventId}/approval-requests?status&page&pageSize` | TENANT_ADMIN, REVIEWER, APPROVER |
| GET | `/api/v1/admin/events/{eventId}/approval-audit-events?page&pageSize` | TENANT_ADMIN, REVIEWER, APPROVER |
| GET | `/api/v1/admin/events/{eventId}/workflow-summary` | 조회 역할. 저장 행 집계만 제공 |
| GET | `/api/v1/admin/events/{eventId}/reservation-imports?page&pageSize` | TENANT_ADMIN, ANALYST, REVIEWER |
| GET | `/api/v1/admin/track1/clusters/detail`의 `graph` | 기존 조회 역할 |

`graph`는 저장된 device/IP 공유 근거만 사용합니다. `PAYMENT`, `ADDRESS` 간선과 `reservation` 노드는 근거가 저장되지 않아 반환하지 않으며, 노드 id는 군집별 불투명 값입니다.
