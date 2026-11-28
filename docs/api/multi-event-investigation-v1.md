# 다공연 조사 분석 API v1

이 계약은 회차별 Track 1 exp1 및 exp2 계약과 별도다. 결과는 조사 후보이며 불법 조직,
동일인, 위반을 확정하지 않는다.

## 권한과 범위

- 모든 경로는 관리자 JWT와 event 기반 역할 확인을 거친다.
- 요청 본문에는 tenantId가 없다. 서버가 각 eventId 권한에서 tenant를 해석한다.
- event는 1개 이상이면 되고, 선택된 공개 원장 head 기준으로 서로 다른 performance(회차)가 2개 이상이어야
  한다. 같은 공연의 다른 회차도 반복 근거다. 회차가 1개뿐이면 `MULTI_EVENT_PERFORMANCE_SCOPE_REQUIRED`로
  거절한다. 모든 event는 같은 tenant 및 같은 관리자 membership 범위여야 한다.
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
| GET | `/api/v1/admin/multi-event-investigations/events/{eventId}/{runId}/candidate-families` | 묶음 점수 내림차순으로 후보 가족을 페이지 단위로 반환한다. 한 가족의 후보는 같은 응답에 포함한다. |
| GET | `/api/v1/admin/multi-event-investigations/events/{eventId}/{runId}/candidates/{candidateId}/members` | 불투명 구성원 ID를 페이지 단위로 반환한다. |
| GET | `/api/v1/admin/multi-event-investigations/events/{eventId}/{runId}/candidates/{candidateId}/relationships` | 신호 종류와 관계 계수만 반환한다. |
| GET | `/api/v1/admin/multi-event-investigations/events/{eventId}/{runId}/sources` | 고정 원장 출처를 반환한다. |

`pageSize`는 1부터 100까지다. 다른 tenant 범위는 기존 404 정책을 따른다.

## 후보 가족과 조사 근거

- 후보 가족은 같은 분석 run 안에서만 계산한다. 후보 계정 집합이 다른 후보의 진부분집합이면 가장 작은 상위 집합을 직접 부모로 둔다. 같은 크기의 부모 후보가 여럿이면 candidate ID 오름차순 첫 후보를 고른다. 부분 겹침은 가족으로 묶지 않는다.
- `familyId`는 부모 연결을 따라간 최상위 후보 ID다. `childCount`는 직접 자식 수다. `familyScore`는 가족 구성원 중 가장 높은 개별 조사 점수이며 개별 `investigationScore`와 S/L/G/B 값은 바꾸지 않는다.
- 가족 메타데이터 마이그레이션 전에 생성된 후보는 `candidateId`를 가족 ID로, 개별 점수를 가족 점수로 사용해 단독 가족으로 반환한다.
- 기존 후보 페이지는 호환성을 위해 유지한다. 화면은 `candidate-families` 경로를 사용해 가족 경계가 페이지 사이에서 갈라지지 않게 한다.
- 후보 `reasons`는 코드, 근거 계정 수, 관련 performance ID, 후보 안에서의 결정적 순위(`strengthRank`)를 담는다. 순위는 근거 계정 수 내림차순, 관련 회차 수 내림차순, 코드 오름차순이다. 동점도 코드 순서로 안정화한다.
- 신호 근거의 `count`는 해당 코드의 검증된 공유 token 그룹에 한 번 이상 참여한 서로 다른 계정 수다. 구성원 근거 `peerCount`는 해당 구성원이 한 공유 token 그룹에서 확인된 다른 계정의 최댓값이다. 실제 token 값은 근거 API 응답에 싣지 않는다.
- 현재 구현된 코드는 환불 계좌, 연락처, 기기, 배송지, 10분 IP 시간창, 반복 회차다. 좌석 재예매 연쇄, 공연 간 연속성, 수량 근거는 해당 PR 기능이 구현되기 전에는 출력하지 않는다.
- Backend는 내부 인증된 Data `member-reasons` 페이지를 받아 HMAC 키와 근거 JSON을 별도 테이블에 저장한다. 공개 관리자 API는 이 HMAC을 반환하지 않는다.

## 점수 버전

- 새 실행은 `score-v2-exp2-multi-performance-recurrence`만 저장하고 Data에 보낸다. R은 사전 등록 정의
  `min((서로 다른 회차 수 - 1) / 3, 1)`로 검산한다.
- 이전 값 `score-v2-exp2-multi-event-recurrence`는 퇴역했다. 이 값으로 이미 저장된 실행은 조회만 가능하다.
  재시도는 `SCORE_VERSION_RETIRED`로 거절하고, worker가 집어 든 미완료 실행은 `BLOCKED`와
  `SCORE_VERSION_RETIRED`로 끝낸다. Data도 새 요청의 이전 값을 같은 코드로 거절한다.
- 회차 edge 가중치 버전 `score-v2-exp2`와 projection 버전은 바뀌지 않았다.

## MySQL migration 순서

기존 BL-07 및 exp2 신호 migration을 적용한 뒤 다음 순서를 유지한다.

1. `multi-event-investigation-migration.sql`
2. `multi-event-quantity-snapshot-migration.sql`
3. `multi-event-source-binding-migration.sql`
4. `multi-event-artifact-job-migration.sql`
5. `bulk-ledger-track1-signal-source-migration.sql` (v3 원본 수신의 신호 출처 컬럼)
6. `multi-event-multi-performance-migration.sql` (새 점수 버전과 퇴역 버전 동시 허용, 후보 `distinct_event_count >= 1`)
7. `multi-event-candidate-family-reasons-migration.sql` (후보 가족 및 구성원 근거, 추가형)

대량 원장 쪽 v3 스테이징 migration을 포함한 전체 순서는 `docs/operations/bulk-ledger-v3-raw-intake.md`를 따른다.

마지막 migration은 선택된 원장 버전이 같은 tenant, event, performance에 속하는지와 정책
snapshot이 그 원장 버전을 참조하는지 복합 외래 키로 고정한다.

## Data 경계

Spring은 소규모 실행에서 고정된 scope, 목적 `TRACK1_CONNECTION`, 단일 supplier namespace와 단일 key version을
내부 인증된 `/api/v2/multi-event/analyze`로 보낸다. 응답의 최종 score, edge projection score,
projection, GDS runtime 버전을 각각 검증한다. 구성 계정이 입력에 존재하는지와 관계별 근거 및 고정
source scope를 재검증한다.

### 연결 조건과 점수 구성의 재검증

Data의 관계 유형(연결 조건)과 `availableSignals`(점수 구성)는 서로 다르며 Spring은 둘을 따로 검증한다.

- 관계 유형은 강한 신호 4종(`refund_account_hash`, `phone_hash`, `device_id_hash`, `address_hash`)과
  `stable_account_cocluster`다. 모든 관계는 서로 다른 회차 2개 이상에 걸쳐야 한다. 강한 신호 관계는 그
  신호가 `availableSignals`에 들어 있어야 하며, 각 출처 회차에서 구성원 전원이 같은 token을 공유했는지
  확인한다.
- `stable_account_cocluster`는 같은 계정 2개 이상이 서로 다른 두 회차의 GDS Louvain 결과에서 같은 군집에
  있었다는 근거다. Spring은 구성원 전원이 각 출처 회차의 고정 원장에서 분석 기간 안의 ACTIVE 티켓을
  가지는지, 즉 계정과 회차 존재만 확인한다.
- **한계:** Louvain 군집 자체는 Spring이 다시 계산하거나 검증할 수 없다. 같은 군집이었다는 사실은 Data의
  회차별 GDS 실행 결과를 신뢰한 것이며, 이 관계는 계정 존재와 회차 출처 이상을 증명하지 않는다.
  결제와 IP 시간창은 서로 다른 계정을 잇는 단독 연결 조건이 아니며, 같은 계정 집합의 반복 동시 군집은
  계정 연속성에 근거한 연결이다.
- `availableSignals`는 exp2 신호 10종(강한 신호 4종, `payment_hash`, 망 유형별 `ip_window:*` 5종)만
  받는다. Spring은 신호마다 구성원 2명 이상이 출처 회차 하나 이상에서 실제로 공유했는지 SQL로 확인한다.
  `ip_window:<유형>`은 같은 `ip_hmac`과 같은 600초 구간에서 공유했는지, 그 구간의 망 유형이 하나면 그 유형,
  섞였으면 `UNKNOWN`인지까지 확인한다. 구간은 Data의 `int(purchasedAt.timestamp()) // 600`과 같도록
  `timestampdiff(second,'1970-01-01 00:00:00',purchased_at) div 600`으로 계산하며, 경계 시각 고정 벡터
  테스트로 두 계산이 같음을 확인한다. hub 제외 여부는 다시 판정하지 않는다.
- `unavailableSignals`는 기본 신호 5종과 IP 시간창 전체를 뜻하는 `ip_window`만 받는다.
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
