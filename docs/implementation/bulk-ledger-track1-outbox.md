# BL-06 공개 원장 Track 1 outbox 분석

`LEDGER_PUBLISHED` outbox는 공개 당시 고정된 `version_id`만 소비한다. worker는 기본적으로 꺼져 있고, 합성 격리 tenant 및 `TRACK1_V2_ENABLED`를 모두 통과해야만 Data `/api/v2/clusters/analyze`를 호출한다.

- outbox lease/fence는 worker 중단 뒤 `RETRYABLE` 재선점을 허용한다. 공개, 소비 시작, FastAPI 성공은 별도 상태다.
- revision은 generation 0부터 base chain을 검증해 재구성하며, 같은 ticket HMAC의 마지막 revision만 입력한다. 최신 publication head는 읽지 않는다.
- `td_bulk_ledger_track1_ticket_signals`는 공개 version와 ticket HMAC, HMAC key version에 고정된 명시적 연결 토큰이다. reference HMAC에서 결제/기기/주소/IP를 추정하거나 Track 2 purpose HMAC을 결합하지 않는다.
- 신호, 계보, ACTIVE/TICKET 근거가 없으면 `BLOCKED`로 남기며 정상 0건으로 바꾸지 않는다. `CANCELLED`, `REFUNDED`, REMOVE 정책은 이 경로에서 처리하지 않는다.
- Track 1 결과는 기존 `track1_purchase_limit_runs` 및 cluster/finding 결과 테이블에 version/outbox 출처와 함께 저장한다. outbox 소비자는 외부 HTTP 중 DB transaction을 유지하지 않는다.

신호 등록 API는 `POST /api/v1/admin/events/{eventId}/ledger-track1/versions/{versionId}/signals`이다. 요청에는 원문이 아닌 64자리 HMAC과 `hmacKeyVersion`, `sourceVersion`만 포함하며 TENANT_ADMIN 및 합성 게이트가 필요하다.
