# 다공연 조사 분석 API v1

이 계약은 회차별 Track 1 exp1 및 exp2 계약과 별도다. 결과는 조사 후보이며 불법 조직,
동일인, 위반을 확정하지 않는다.

## 권한과 범위

- 모든 경로는 관리자 JWT와 event 기반 역할 확인을 거친다.
- 요청 본문에는 tenantId가 없다. 서버가 각 eventId 권한에서 tenant를 해석한다.
- 두 개 이상의 서로 다른 event가 같은 tenant 및 같은 관리자 membership 범위여야 한다.
- 서버는 공개된 원장 head만 고정하고, canonical 정렬한 `(eventId, performanceId, versionId)`의
  SHA-256을 `ledgerVersionSetDigest`로 저장한다.
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

## Data 경계

Spring은 고정된 scope, 목적 `TRACK1_CONNECTION`, 단일 supplier namespace와 단일 key version을
내부 인증된 `/api/v2/multi-event/analyze`로 보낸다. Data 응답의 scope, 구성원, 관계 신호는
요청 범위를 다시 검증한다. IP와 payment 신호만으로 공연 간 후보를 연결하지 않는다.

HMAC 값은 Spring 내부 저장과 Data 내부 계약에서만 사용한다. 관리자 API와 Frontend에는
원본 HMAC, 원본 개인정보, 객체 저장소 key를 반환하지 않는다.

## 상태

`PENDING`, `RUNNING`, `RETRYABLE`, `FAILED`, `BLOCKED`, `COMPLETED`가 분리된다. 실패 또는
차단 상태의 빈 후보는 정상 0건이 아니다. worker는 lease와 fence token을 사용하며 완료 시에만
결과와 source binding을 저장한다.
