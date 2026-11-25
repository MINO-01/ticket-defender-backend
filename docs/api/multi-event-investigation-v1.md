# 다공연 조사 분석 API v1

이 계약은 회차별 Track 1 exp1 및 exp2 계약과 별도다. 결과는 조사 후보이며 불법 조직,
동일인, 위반을 확정하지 않는다.

## 권한과 범위

- 모든 경로는 관리자 JWT와 event 기반 역할 확인을 거친다.
- 요청 본문에는 tenantId가 없다. 서버가 각 eventId 권한에서 tenant를 해석한다.
- 두 개 이상의 서로 다른 event가 같은 tenant 및 같은 관리자 membership 범위여야 한다.
- 서버는 공개된 원장 head와 완료된 bulk 정책 snapshot 참조를 생성 시 고정한다. canonical
  `(eventId, performanceId, versionId)`의 SHA-256은 `ledgerVersionSetDigest`에, policy snapshot
  집합은 idempotency request digest에 바인딩한다.
- 실행 목록은 고정된 모든 event의 현재 membership grant가 있는 행만 SQL에서 pagination 전에
  반환한다. 상세 및 모든 후속 조회/재시도는 해당 run 전체 scope의 현재 권한을 다시 검사한다.
- 서로 다른 supplier namespace, HMAC purpose, HMAC key version은 요청 전에 차단한다.

## 경로

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/api/v1/admin/multi-event-investigations` | eventId 목록과 선택 performanceId 목록으로 비동기 실행을 생성한다. `Idempotency-Key`가 필요하다. |
| GET | `/api/v1/admin/multi-event-investigations/events/{eventId}/performances` | 현재 권한과 공개 head가 확인된 선택 가능 회차를 반환한다. |
| GET | `/api/v1/admin/multi-event-investigations/events/{eventId}` | 해당 event 범위에 속한 실행 목록을 반환한다. |
| GET | `/api/v1/admin/multi-event-investigations/events/{eventId}/{runId}` | 실행 상태를 반환한다. |
| GET | `/api/v1/admin/multi-event-investigations/events/{eventId}/{runId}/candidates` | 후보를 페이지 단위로 반환한다. |
| GET | `/api/v1/admin/multi-event-investigations/events/{eventId}/{runId}/candidates/{candidateId}/members` | 불투명 구성원 ID를 페이지 단위로 반환한다. |
| GET | `/api/v1/admin/multi-event-investigations/events/{eventId}/{runId}/candidates/{candidateId}/relationships` | 신호 종류와 관계 계수만 반환한다. |
| GET | `/api/v1/admin/multi-event-investigations/events/{eventId}/{runId}/sources` | 고정 원장 출처를 반환한다. |

`pageSize`는 1부터 100까지다. 다른 tenant 범위는 기존 404 정책을 따른다.

## MySQL migration 순서

기존 BL-07 및 exp2 신호 migration을 적용한 뒤 다음 순서를 유지한다.

1. `multi-event-investigation-migration.sql`
2. `multi-event-quantity-snapshot-migration.sql`
3. `multi-event-source-binding-migration.sql`
4. `multi-event-artifact-job-migration.sql`

마지막 migration은 선택된 원장 버전이 같은 tenant, event, performance에 속하는지와 정책
snapshot이 그 원장 버전을 참조하는지 복합 외래 키로 고정한다.

## Data 경계

Spring은 소규모 실행에서 고정된 scope, 목적 `TRACK1_CONNECTION`, 단일 supplier namespace와 단일 key version을
내부 인증된 `/api/v2/multi-event/analyze`로 보낸다. 응답의 최종 score, edge projection score,
projection, GDS runtime 버전을 각각 검증한다. 구성 계정이 입력에 존재하는지와 관계별 실제
공유 신호 및 고정 source scope를 재검증한다. IP와 payment 신호만으로 공연 간 후보를 연결하지 않는다.
티켓 행은 고정 scope별 순번을 포함해 같은 예약의 여러 티켓을 별도 입력으로 보존한다. 이 순번은
중복 검사에만 사용하며 계정 연결 키가 아니다.

소규모 JSON 분석 경로는 50,000 ticket 제한을 유지한다. 대용량 작업은 Spring이 비공개 저장소에
불변 NDJSON artifact를 생성하고 Data의 비동기 job을 등록한다. manifest에는 실제 VersionId,
SHA-256, 행 수, 고정 원장 범위, tenant, 시점 및 모델 버전이 포함된다. Data 작업 상태와 후보,
구성원, 관계 페이지를 재개 가능하게 수집하고 모든 고정 scope, 중복 및 총계 검증이 끝난 뒤에만
MySQL 결과를 완료 상태로 공개한다. artifact 경로와 HMAC은 관리자 API에 노출하지 않는다.

대용량 계약은 `multi-event-exp2-ndjson-v1` 형식으로 최대 400,000행을 수용한다. 이는 입력 및
결과 처리 구조의 지원 용량이며 실제 400,000행 GDS 성능 실험 통과를 뜻하지 않는다.
Spring은 기존 private bulk upload adapter의 storage prefix 아래에 run/fence 기반 불투명 객체명을
사용한다. Data에는 객체명과 S3 VersionId만 전달하고 별도 bucket/prefix 설정으로 같은 객체를 읽는다.
artifact manifest는 fixed source event UUID, 외부 event 참조, performance/version, supplier namespace,
purpose/key version, 분석 시각과 digest를 고정한다. 서버는 artifact bytes를 1 GiB 이하로 제한한다.
Data job 상태의 digest와 input/processed row 수를 검사하고, 완료 시 Data `candidateCount`와 모든
페이지의 `totalCount`, cursor 연속성 및 고정 scope를 대조한다. 페이지별 저장은 lease/fence 검사 후
수행하며, 재개 시 미완료 run의 부분 결과만 지우고 다시 가져온다.
Data가 작업 큐 포화 코드 `MULTI_EVENT_JOB_QUEUE_FULL`로 HTTP 429를 반환하면 Backend는 고정 artifact를
유지하고 lease 없는 `DATA_PENDING`으로 되돌려 다음 worker tick에 동일 Idempotency-Key로 재등록한다.
큐 포화 응답은 분석 시도를 소모하지 않는다.
결과 공개 전 Backend는 후보 페이지와 관계 요약 페이지를 사전 확인해 총 구성원 행과
관계-구성원 행 각각 5,000,000개를 넘지 않게 제한한다. 초과하면 해당 분석을 차단하고 일부 결과를
완료 상태로 공개하지 않는다.

신규 `multi-event-artifact-job-migration.sql`은 기존 다공연 migration과 quantity/source migration
뒤에 적용해야 한다. `DATA_PENDING`은 Data 비동기 작업이 대기 중이거나 결과 페이지를 재개 수집하는
상태이며 lease를 보유하지 않는다.

HMAC 값은 Spring 내부 저장과 Data 내부 계약에서만 사용한다. 관리자 API와 Frontend에는
원본 HMAC, 원본 개인정보, 객체 저장소 key를 반환하지 않는다.

## 상태

`PENDING`, `RUNNING`, `DATA_PENDING`, `RETRYABLE`, `FAILED`, `BLOCKED`, `COMPLETED`가 분리된다. 실패 또는
차단 상태의 빈 후보는 정상 0건이 아니다. worker는 lease와 fence token을 사용하며 완료 시에만
결과와 source binding을 저장한다.
