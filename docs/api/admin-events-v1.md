# 관리자 접근 가능 공연 목록 API 연동 안내 (Issue #56)

계약 원문은 [admin-events-v1.yaml](admin-events-v1.yaml)이다.

## 요청

- `GET /api/v1/admin/events?page=0&pageSize=50` (pageSize 최대 100)
- `Authorization: Bearer <관리자 JWT>`
- tenantId, 역할, 공연 이름은 보내지 않는다. 보내도 무시되며 권한 근거가 되지 않는다.

## 응답

`{ items: [{ id, externalEventId, name }], page, pageSize, totalItems, hasNext }`, 정렬은 name 오름차순 후 id 오름차순, `Cache-Control: no-store`.

## tenant 선택 정책

| 활성 고객사의 활성 멤버십 수 | 결과 |
| --- | --- |
| 0 | 200 빈 목록 |
| 1 | 200, 그 고객사에서 권한이 부여된 활성 공연만 |
| 2 이상 | 409 `TENANT_SELECTION_REQUIRED`. 고객사를 임의로 고르거나 합치지 않음 |

여러 고객사 소속 관리자의 선택 방식은 Specs 결정 로그 DEC-02가 PENDING이라 미지원이다. 후보는 인증 제공자의 서명된 tenant 클레임, 서버 멤버십 기반 명시적 선택 등이며, 정책 확정 후 별도 Issue로 다룬다.

## 역할

TENANT_ADMIN, ANALYST, REVIEWER, APPROVER 중 하나 이상이면 목록을 볼 수 있다. 역할이 없는 멤버십은 403 `FORBIDDEN`이다. 목록에 보이는 공연이라도 개별 업무 API는 각자의 역할 정책을 다시 적용한다.

| 화면 | API | 허용 역할 |
| --- | --- | --- |
| Track 1 군집 | `GET /api/v1/admin/track1/clusters?eventId=` | TENANT_ADMIN, ANALYST, REVIEWER, APPROVER |
| Track 2 팬 제보 | `GET /api/v1/admin/fan-reports?eventId=` | ANALYST, REVIEWER |
| Case 목록 | `GET /api/v1/cases?eventId=` 등 Case API | 조회 TENANT_ADMIN, ANALYST, REVIEWER (작업별 상이) |

역할이 맞지 않으면 개별 API가 403을 반환하므로 Frontend는 이를 권한 부족 안내로 처리한다.

## Frontend eventId 선택 흐름

1. 로그인 후 `GET /api/v1/admin/events` 호출
2. 200이고 items가 있으면 공연 선택 UI 표시, 선택한 `items[].id`를 각 화면 API의 `eventId`로 사용
3. 200 빈 목록이면 "접근 가능한 공연 없음" 표시 (장애가 아님)
4. 409 `TENANT_SELECTION_REQUIRED`이면 "여러 고객사 소속 계정은 아직 지원하지 않음" 안내
5. 401은 재로그인, 403은 권한 부족, 503은 일시 장애로 표시하고 빈 목록으로 대체하지 않음

## 오류

| 상태 | code |
| --- | --- |
| 400 | `INVALID_REQUEST` |
| 401 | 본문 없음 (`WWW-Authenticate`) |
| 403 | `FORBIDDEN` |
| 404 | 이 API는 반환하지 않음 |
| 409 | `TENANT_SELECTION_REQUIRED` |
| 503 | `DEPENDENCY_UNAVAILABLE` |

## 인덱스 검토

멤버십 조회는 `subject` 조건이며 기존 유니크 키 `(tenant_id, subject)`의 선두 열이 아니어서 멤버십 테이블을 스캔한다. 멤버십 행 수는 관리자 수에 비례해 작고 결과는 최대 2건만 읽으므로 이번에는 migration을 추가하지 않았다. 관리자 규모가 커지면 `subject` 단독 인덱스 추가를 검토한다. 공연 조회는 `idx_events_tenant_id(tenant_id, id)`와 `uk_membership_events_member_event(membership_id, event_id)`를 사용한다.
