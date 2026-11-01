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

### 관리자 업무 API 임시 차단 (Issue #58, DEC-02 확정 전 임시 정책)

DEC-02의 최종 고객사 선택 기능이 아니다. 공연 ID를 직접 지정해 목록 차단을 우회하지 못하도록 업무 API도 같은 기준으로 막는다.

- 기준: 검증된 관리자 JWT subject와 정확히 같은 subject의 활성 멤버십이 서로 다른 활성 고객사 2곳 이상에 있으면 409 `TENANT_SELECTION_REQUIRED`. 비활성 고객사와 비활성 멤버십은 세지 않는다. MySQL 기본 collation에서 대소문자만 다른 subject 행도 세지 않는다.
- 적용 위치: `AdminAccessService.requireResourceAccess`(`requireEventAccess` 포함). 서버가 저장된 자원에서 고객사와 공연을 정하고 해당 멤버십과 공연 권한을 확인한 뒤, 역할 검사 직전에 판단한다. 클라이언트가 보낸 tenantId는 근거로 쓰지 않는다.
- 순서: 401 인증 실패 → 404 없는 자원, 다른 고객사 자원, 권한 없는 공연 → 409 다중 고객사 → 403 역할 부족. 409는 이미 소속이 확인된 고객사 자원에서만 나오므로 다른 고객사 자원의 존재를 드러내지 않는다.
- 적용 API: Track 1 군집 목록과 상세, Track 2 팬 제보 목록과 상세 및 목록 맥락 분석, Case 생성과 조회와 상태 전이와 담당자 지정, EvidenceSnapshot, Evidence 검증과 무결성 평가, ReportVersion, 승인 요청과 조회와 검토 패키지와 결정과 철회, 공식 PDF 적격성과 발급과 재시도와 조회와 다운로드.
- 응답: `application/problem+json`, `title`, `status`(409), `instance`(요청 경로), `code`, `requestId`. 고객사, 공연, 증거, 보고서 정보는 담지 않는다. Track 1처럼 `CaseErrorHandler` 범위 밖인 컨트롤러도 `AdminTenantSelectionErrorHandler`가 같은 형식으로 응답한다.
- 보존: 차단은 저장된 승인 요청, 승인 결정, 검토 영수증, 증거, 보고서, 공식 PDF를 바꾸거나 취소하거나 무효화하지 않는다. 자동 승인 무효화 경로도 실행하지 않는다.
- 비적용: 공개 팬 제보 접수와 조회, 내부 분석 서버 인증 경로, 기존 레거시 PDF 경로(`/api/v1/audits/*/report`, 기존과 같이 항상 409 차단).
- 비용: 업무 요청마다 subject 기준 활성 멤버십 조회가 1회 추가된다. 캐시는 두지 않아 멤버십 변경이 다음 요청에 바로 반영된다. 아래 인덱스 검토 참고.

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

## DB 장애 처리 (Issue #59)

- 조회 중 `DataAccessException`은 503 `DEPENDENCY_UNAVAILABLE`로 응답한다. 빈 목록이나 0건으로 대체하지 않는다.
- 트랜잭션 시작 단계의 `CannotCreateTransactionException`은 원인 체인에 DB 연결 획득 실패가 있을 때만 503으로 응답한다. 판단 기준은 JDBC 표준 연결 예외 타입(`SQLTransientConnectionException`, `SQLNonTransientConnectionException`) 또는 SQL 표준 연결 예외 분류인 SQLState `08` 계열이다. 예외 메시지 문자열은 비교하지 않는다.
- 연결과 무관한 원인(예: `IllegalStateException`, SQLState `08`이 아닌 `SQLException`)으로 트랜잭션 시작이 실패하면 503으로 바꾸지 않고 다시 던져 기본 500 처리에 맡긴다. 그 밖의 `TransactionException`과 일반 서버 결함도 기본 500 처리를 유지한다.
- 503 본문은 `application/problem+json` ProblemDetail이며 `title`(`Service Unavailable`), `status`(503), `instance`(요청 경로), `code`, `requestId`를 담는다. `type`은 기본값 `about:blank`라 직렬화에서 생략되고 `detail`은 넣지 않는다. DB 주소, 포트, 계정, 드라이버 오류 메시지와 내부 예외 메시지는 노출하지 않는다.
- 같은 `CaseErrorHandler`를 쓰는 Case, 승인, 공식 PDF, Track 2 관리자 API도 같은 기준을 적용한다. Track 1 군집 API는 이 처리기 범위가 아니므로 이번 변경으로 오류 계약이 바뀌지 않는다.
- 격리 MySQL 8 검증: 연결 풀 접속 정보를 바꿔 실제 MySQL이 새 연결을 거부하게 하면 `CannotCreateTransactionException`(원인 `JDBCConnectionException`, `SQLTransientConnectionException`, MySQL `Access denied`)이 발생하고 수정 전에는 500이었다. 수정 후 503을 반환하며, 접속 정보 복구 후 연결 풀 재시도 대기를 거쳐 같은 요청이 200 정상 목록을 반환한다. 멤버십이 없는 계정은 장애 중 503, 정상 시 200 빈 목록으로 구분된다.
- 실제 Keycloak 토큰을 쓰는 HTTP E2E 재검증은 Frontend E2E 환경에서 별도로 기록해야 한다.

## 인덱스 검토

멤버십 조회는 `subject` 조건이며 기존 유니크 키 `(tenant_id, subject)`의 선두 열이 아니어서 멤버십 테이블을 스캔한다. 멤버십 행 수는 관리자 수에 비례해 작고 결과는 subject의 소속 고객사 수만큼이므로 이번에는 migration을 추가하지 않았다. Issue #58 이후 이 조회는 관리자 업무 요청마다 실행된다. 관리자 규모가 커지면 `subject` 단독 인덱스 추가를 검토한다. 공연 조회는 `idx_events_tenant_id(tenant_id, id)`와 `uk_membership_events_member_event(membership_id, event_id)`를 사용한다.
