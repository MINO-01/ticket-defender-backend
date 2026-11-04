# Track 2 회차 검증 및 예매 후보 대조 파이프라인 (#68)

상태: 합성 격리 환경 구현. 실제 고객사 예매 데이터 반입, 보존기간, 고객사 원본 조회 권한은 이 문서나 구현으로 승인되지 않는다.

## 경계와 기존 구현 보존

- 기존 `Reservation`의 전역 `reservationNo` UNIQUE 및 기존 행은 바꾸지 않는다. 범위 없는 기존 Reservation은 새 후보 대조의 입력이 아니다.
- 기존 `ListingContextAnalysis`와 `listing_context_observations`는 이미지 분석 이력이다. `candidateMatch=NOT_LINKED` legacy 요약 필드를 갱신하지 않는다.
- 새 `listing_candidate_match_runs`가 후보 조회 상태를 별도로 보존한다. 이력은 update하지 않으며, 회차/소스 변경 후에도 과거 run을 덮어쓰지 않는다.
- `VlmAnalysisService`의 `mappingScore` 경로와 `FastApiAdapter`의 Track 1 계약은 사용하거나 변경하지 않는다. Listing Context 후보 대조에는 mappingScore가 없다.
- `MATCH_CANDIDATE`는 단일 조사 후보일 뿐 판매자, 불법성, 사기, Case 적합성 또는 제재를 확정하지 않는다.

## 회차와 권한

`verified_performances`는 `(tenantId,eventId,performanceRef)`의 ACTIVE/WITHDRAWN 상태, 관리자 검증 근거, 출처 버전, revision, 수행자 및 시각을 보존한다. `performance_verification_audits`는 VERIFIED/REVISED/WITHDRAWN append-only 기록이다.

REVIEWER 또는 TENANT_ADMIN만 회차와 제보 연결을 만들거나 바꿀 수 있다. `fan_report_performance_links`와 `performance_link_audits`는 연결/교체/철회를 별도 행으로 보존한다. 다른 tenant/event 자원은 404, 같은 범위에서 역할이 부족하면 403이다.

연결 교체는 이전 행의 nullable unique active slot을 먼저 닫고 새 행을 저장한다. 경합에서 UNIQUE/audit
제약에 진 요청은 `409 PERFORMANCE_LINK_CONCURRENT_UPDATE`이며, 동일 idempotency key의 승자 commit 뒤
재확인만 기존 연결 결과를 재현한다.

팬 입력 날짜와 VLM `performanceAt`은 어떠한 자동 performanceRef 확정에도 사용하지 않는다. ACTIVE 연결은 해당 performance revision에 묶인다. 회차가 수정·철회되거나 연결이 바뀌면 기존 candidate run은 그대로 두고 GET 응답의 `applicability`만 재계산한다.

## 합성 예매 적재

`POST /api/v1/admin/events/{eventId}/reservation-imports`는 기본적으로 `409 RESERVATION_IMPORT_DISABLED`다. 활성화는 아래가 모두 만족될 때에만 가능하다.

1. `ticket-defender.track2-candidate-import.enabled=true`
2. `operational-mode=SYNTHETIC_ISOLATED`
3. UUID isolation ID
4. H2 memory 또는 loopback MySQL datasource
5. 서버 소유 `synthetic_reservation_import_tenants` 등록
6. 32자 이상 HMAC master key와 key version

등록은 공개 API가 아닌 격리 fixture/운영 준비 단계에서만 한다. 이 게이트는 실제 고객사 데이터임을 증명하지 않으며 운영 활성화 승인을 대신하지 않는다.

입력의 `reservationReference`와 accountId는 저장·로그·응답에 남기지 않는다. 서버는 master key에서 `tenant + TRACK2_RESERVATION_REFERENCE + keyVersion`별 하위 키를 만들고 HMAC-SHA-256만 저장한다. 요청 본문 digest도 길이 접두어와 type token으로 직렬화해 구분자·null·blank·실제 문자열을 구별하며, 원문 예매번호는 이 계산 중 메모리에서만 HMAC token으로 바꾼다. `sourceVersion`은 앞뒤 Unicode 공백을 제거한 저장값으로 먼저 정규화하고 같은 정규화 값을 digest에도 사용한다. HMAC key version도 batch/행에 고정한다. source version은 완전 스냅샷 단위이며, 이전 ACCEPTED batch와 행은 삭제하지 않고 `reservation_data_sources`의 현재 포인터만 바꾼다.

후속 고객사 원본 조회에는 예매번호 대신 `candidateId`를 opaque 참조로 사용한다. 이 ID는 이 서비스의
`reservation_candidates.id`이며, 해석에 필요한 내부 바인딩은 `tenantId`, `reservationBatchId`,
`reservationSourceVersion`, `referenceHmacKeyVersion`, `syntheticIsolationId`다. 향후 별도 승인된 고객사
커넥터만 이 바인딩과 고객사 보관 원본을 대조할 수 있고, API 소비자·CaseSourceVerifier·EvidenceSnapshot은
원본 예매번호나 HMAC 값을 요청하거나 받지 않는다. 이 Issue는 그러한 원본 조회 커넥터, 실제 고객사 반입,
보존 기간을 구현하거나 승인하지 않는다.

batch는 atomic이다. `completeSnapshot=true`가 아니거나 어느 행이든 invalid/중복이면 REJECTED이며 후보 행은 0개다. 따라서 `NO_MATCH`는 현재 source가 ACCEPTED와 complete snapshot으로 검증되고 정확한 좌석 조회가 성공한 경우에만 가능하다. 소스 없음·불완전·교체 중 근거 부족은 `UNVERIFIABLE`이다.

동시에 같은 idempotency/source scope를 새로 만들려다 MySQL UNIQUE 제약에서 지는 요청은 전체 transaction을
rollback하고 `409 RESERVATION_IMPORT_CONCURRENT_UPDATE`를 반환한다. 승자 commit 뒤 같은 idempotency key를
재확인하면 저장된 batch를 재현하며, 다른 본문은 `409 IDEMPOTENCY_KEY_REUSED`다.

좌석 표준화는 앞뒤 Unicode 공백 제거만 수행하며, 대소문자 변환, 문자폭 보정, 추정, 유사도 비교를 하지 않는다. MySQL binary collation으로 `zone,row_num,seat_num`를 exact match한다.

## 후보 실행과 실패 보장 범위

candidate run은 다음 불변 바인딩을 보존한다.

- `runId`, `analysisId`, `analysisRowVersion`, `analysisResponseSha256`
- `fanReportId`, `evidenceId`, `evidenceSha256`
- `performanceLinkId/revision`, `verifiedPerformanceId/revision`
- `reservationBatchId`, `reservationSourceVersion` 및 source revision
- 단일 후보의 opaque `candidateId`

후속 CaseSourceVerifier/EvidenceSnapshot 단계는 원본 예매번호나 HMAC을 받지 않고 위 tuple과 candidate run의 `runId`, evidence/analysis SHA-256, revision, sourceVersion을 재검증해야 한다. 이 Issue는 그 Case 연결 자체를 구현하지 않는다.

상태는 `IN_PROGRESS`, `MATCH_CANDIDATE`, `NO_MATCH`, `AMBIGUOUS`, `UNVERIFIABLE`, `LOOKUP_FAILED`, `ANALYSIS_FAILED`다. 이미지 필드의 FOUND/UNKNOWN/AMBIGUOUS 상태와 독립적이다.

- 분석이나 증거 digest/revision이 맞지 않으면 `UNVERIFIABLE/ANALYSIS_OR_EVIDENCE_CHANGED`
- 좌석 관찰이 UNKNOWN이거나 FOUND 값이 저장 가능한 exact 좌석(공백 제거 후 1~128자)이 아니면 `UNVERIFIABLE/SEAT_OBSERVATION_UNAVAILABLE`, AMBIGUOUS이면 `UNVERIFIABLE/SEAT_OBSERVATION_AMBIGUOUS`
- 정확히 한 행은 `MATCH_CANDIDATE`, 0행은 `NO_MATCH`, 둘 이상은 `AMBIGUOUS`
- 후보 실행 시작, 조회 또는 종료 중 repository 오류가 나도 DB가 복구되어 종료 행을 쓸 수 있으면 `LOOKUP_FAILED`를 보존한다.
- 시작 행조차 만들 수 없거나 DB 전체 장애라서 동일 DB에 종료 행을 쓸 수 없으면 HTTP 503 `CANDIDATE_LOOKUP_FAILED_UNRECORDED`만 보장된다. 이 경우 DB 감사 이력 저장을 주장하지 않는다.

동일 actor/analysis/idempotency key는 같은 run을 재현하며 다른 요청 의미로 같은 키를 재사용하면 409이다. 분석별 active slot은 하나라서 다른 키의 동시 조회는 409 `CANDIDATE_LOOKUP_IN_PROGRESS`다. active run에는 내부 lease가 있으며 만료된 run은 `LOOKUP_FAILED/RESERVATION_LOOKUP_FAILED`의 불변 이력으로 닫힌다. 이후에는 새 idempotency key로 재시도할 수 있고, 늦게 도착한 이전 worker의 complete는 닫힌 행을 덮어쓰지 않는다.

## API 계약과 후속 저장소 영향

정식 경로, 요청/응답, enum, 오류는 `docs/api/track2-candidate-pipeline-v1.yaml`을 따른다. Frontend에는 candidate-matches 별도 이력 API와 `applicability` enum을 사용하도록 전달해야 하며, 기존 Listing analysis 응답의 legacy `candidateMatch`를 MATCH 결과로 해석해서는 안 된다. Data 저장소에는 API 변경이 없고, FastAPI/VLM 응답이나 mappingScore 계약을 변경하지 않는다.

## 테스트 계획

1. 순수 결정 테스트: 완전 source가 없는 0건은 UNVERIFIABLE, 단일/복수/없음, UNKNOWN/AMBIGUOUS, 분석 실패, mappingScore 비사용.
2. 서비스/HTTP 테스트: tenant/event IDOR 404, 역할 403, 회차 revision/철회, link 멱등성·본문 충돌·경합, synthetic gate 차단, batch 중복/부분 실패/retry, PII 미응답.
3. MySQL 8: additive migration, FK/UNIQUE/CHECK/index, JPA validate, active source 및 active candidate slot의 lease 만료·늦은 완료 차단·동시성.
4. 실제 HTTP E2E: 격리 MySQL + synthetic tenant + fake 저장 분석을 이용해 ACCEPTED snapshot, candidate run, source/회차 변경 applicability를 검증한다.
5. 기존 Track 1/Track 2 regression을 실행한다. Docker/MySQL/MinIO를 쓰는 무거운 검증 전에는 프로세스와 RAM을 확인한다.
