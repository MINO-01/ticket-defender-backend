# 합성 격리 시연 실행 가이드

이 폴더는 기존 MySQL 8, Neo4j/GDS, MinIO(Object Lock), Keycloak, FastAPI, Frontend를 삭제·초기화·생성하지 않는다. 인프라 담당자가 기동한 뒤 `./demo/preflight.ps1 -RequireRunning`으로 연결과 필수 플래그만 검사한다. `./demo/start.ps1 -Spring`은 이 Backend만 foreground로 시작한다.

## 필수 환경 변수

`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `FASTAPI_URL`, `TRACK1_INTERNAL_SHARED_SECRET`, `FRONTEND_URL`, `TRACK1_V2_ENABLED=true`, `TRACK1_INTERNAL_READ_TIMEOUT=PT60S`, `OFFICIAL_PDF_ENABLED=true`, `OFFICIAL_PDF_DEMO_V1_ENABLED=true`, `OFFICIAL_PDF_OPERATIONAL_MODE=SYNTHETIC_ISOLATED`, `OFFICIAL_PDF_SYNTHETIC_ISOLATION_ID`, `TRACK2_CANDIDATE_IMPORT_ENABLED=true`, `TRACK2_CANDIDATE_IMPORT_OPERATIONAL_MODE=SYNTHETIC_ISOLATED`, `TRACK2_CANDIDATE_IMPORT_SYNTHETIC_ISOLATION_ID`, `TRACK2_CANDIDATE_IMPORT_REFERENCE_HMAC_MASTER_KEY`, `TRACK2_CANDIDATE_IMPORT_REFERENCE_HMAC_KEY_VERSION`, MinIO/S3 endpoint·bucket·access-key 환경 변수가 필요하다. 키와 비밀번호 값은 문서·스크립트·로그에 넣지 않는다.

Keycloak에는 같은 합성 tenant/event에 `TENANT_ADMIN`, `ANALYST` 또는 `REVIEWER`, 그리고 서로 다른 subject의 `APPROVER`를 만든다. 독립 승인자는 요청자와 동일 subject가 될 수 없다.

## MySQL 적용 순서

기존 기본 스키마와 `admin-membership-event-migration.sql`, `track1-tenant-scoped-evidence-migration.sql`, Track 2 reservation/candidate migrations, `track1-purchase-ledger-migration.sql`을 적용한 뒤 `docs/database/track1-capstone-demo-v1-migration.sql`을 적용한다. 마지막 migration은 additive이며, `reference_purpose`가 이미 있으면 다시 추가하지 않는다.

## 합성 입력 계약과 세 시나리오

1. TENANT_ADMIN으로 tenant/event/performance UUID를 생성·조회하고, ACTIVE verified performance와 구매 제한 정책을 등록한다.
2. Track 2 후보용 예약 원장을 같은 tenant/event/performance에 complete snapshot으로 반입한다. Track 1 수량 원장 반입에도 같은 원문 `reservationReference`를 사용한다. HMAC이나 원문을 API 응답에 넣지 않는다.
3. `POST /api/v1/admin/events/{eventId}/track1-quantity/ledger-imports`, 이어서 `/analysis-runs`로 v2 분석을 실행하고 `GET .../analysis-runs/{runId}`에서 관찰값을 확인한다.
4. GRAPH_CLUSTER는 저장 Louvain 군집만 Case로 연결한다. FAN_REPORT는 실제 FastAPI/Gemini 분석이 저장된 팬 신고와 현재 `MATCH_CANDIDATE`를 Case에 연결한다. COMBINED는 두 출처를 연결하고 Track 1/2 링크가 `LINKED`인지 확인한다.
5. 각 Case에서 EvidenceSnapshot과 ReportVersion을 고정하고, 원본 SHA-256 및 MinIO Object Lock 검증 후 요청자와 다른 APPROVER가 승인한다. 공식 PDF API로 발급·다운로드·감사 이력을 확인한다.

`MATCH_CANDIDATE`는 부정 거래 확정이 아니다. DEMO_V1도 현재 분석, source/batch/version, evidence verification, tenant/event 권한, STALE, IssuanceHold와 독립 승인을 우회하지 않는다. run960이나 실제 고객 데이터는 사용하지 않는다.
