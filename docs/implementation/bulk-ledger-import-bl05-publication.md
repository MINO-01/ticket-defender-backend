# BL-05 원장 후보 원자적 공개 계약

`POST /api/v1/admin/ledger-imports/{importId}/publish`는 v2 `READY_TO_PUBLISH` import의 모든 서버 등록 scope를 한 번에 공개한다. 클라이언트는 회차나 version ID를 보낼 수 없으며, 기존 관리자 JWT·TENANT_ADMIN·tenant/event 권한과 합성 격리 gate를 통과해야 한다.

후보 생성은 회차의 `verified_performances` 행을 잠근 뒤 `expected_head_known=1`과 `expected_head_version_id`를 기록한다. 값이 `null`이면 **최초 공개가 예상되었다**는 뜻이고, `expected_head_known=0`은 구 migration의 근거 없는 후보이므로 공개를 차단한다.

공개는 짧은 MySQL 트랜잭션 안에서 scope를 performance ID 순서로 잠근 뒤 다음을 재검증한다.

- import는 v2 `READY_TO_PUBLISH`, candidate와 모든 version은 `READY`다.
- candidate가 고정한 object version/SHA-256, scope, 출처 checkpoint, 기대 head가 import와 현재 DB에 일치한다.
- FULL_SNAPSHOT은 revision 0이며, 현재 head가 CHANGE_FEED여도 그 generation의 공개 revision 0 기준시각과 같은 출처를 잠가 역행을 검사한다. 같은 출처의 후보 `sourceSequenceEnd`는 현재 공개 head checkpoint보다 작을 수 없다. 후보/공개 head checkpoint 또는 revision 0 기준시각·출처가 없으면 서로 다른 출처를 추측해 비교하지 않고 공개를 차단한다. CHANGE_FEED는 현재 공개 base, generation/revision, cursor와 source sequence 연속성이 일치한다.

검증이 모두 끝난 뒤에만 head, version/import/candidate `PUBLISHED`, 불변 발행 이력 및 scope별 `LEDGER_PUBLISHED`/`PENDING` outbox를 같이 저장한다. outbox는 publication/version으로 계산한 불변 deduplication key와 delivery attempt 수를 가지며 분석 실행이 아니고 소비자도 이번 범위에 없다. 어느 INSERT/UPDATE든 실패하면 트랜잭션 전체가 롤백된다. 같은 import 또는 같은 requester·Idempotency-Key 재요청은 이미 확정된 발행 이력을 반환한다.

과거 version, published change event, Track 1 결과, Case/EvidenceSnapshot은 변경하거나 삭제하지 않는다. `REMOVE`, `CANCEL`, `REFUND` action 적용과 분석 실행은 계속 정책 미확정/후속 범위로 차단한다.
