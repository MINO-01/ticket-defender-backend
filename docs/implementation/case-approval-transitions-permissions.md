# Case 상태 전이·역할 권한 매트릭스

> **MVP 정책 반영 / PROPOSED / 미구현.** 상태 이름은 Specs `04_사용자시나리오_업무흐름_상태전이.md`의 제안 상태도를 바탕으로 하되 종료 후 직접 재개는 MVP에서 제외한다. 공유 계약 레지스트리 승인 전이다. `Case.status`, `ApprovalRequest.status`, `ReportVersion.status`, 분석 작업 상태는 별도다.

## Case 상태 전이

공통 원자성 규칙: 성공 시 `caseVersion` 증가, `CaseActivity`와 필요한 `ApprovalAuditEvent`를 같은 트랜잭션에 기록한다. 모든 전이는 인증·tenant/event 범위·기대 버전 검증 후 실행한다. 실패 시 상태·버전·승인 적격성은 바꾸지 않고 403/404/409/422 중 해당 오류와 추적 ID를 반환한다. 전이마다 아래의 구체적 가드를 더 적용한다.

| 이전 → 목표 | 실행 주체 | 추가 사전 조건 | 발생 이벤트 / 감사 이력 | 실패 시 동작 |
|---|---|---|---|---|
| 없음 → `OPEN` | ANALYST 또는 REVIEWER | 적어도 한 유효 출처, 동일 tenant/event, 명시적 결합 사유. 후속 사건이면 종료된 `relatedCaseId`와 `relationReason` 필수, 기존 승인 승계 금지 | `CASE_OPENED` 또는 `FOLLOW_UP_CASE_OPENED` / 출처·원 Case ID·actor·reason | 생성·출처 연결 모두 롤백; 자동 병합 금지 |
| `OPEN` → `TRIAGED` | REVIEWER | 분류 및 담당자 지정 또는 미배정 사유 | `CASE_TRIAGED` / 분류·배정·전후 버전 | 409 또는 422, OPEN 유지 |
| `TRIAGED` → `IN_REVIEW` | REVIEWER | 담당자 권한·공연 범위 확인 | `CASE_REVIEW_STARTED` / actor·사유 | 409, TRIAGED 유지 |
| `IN_REVIEW` → `NEEDS_EVIDENCE` | REVIEWER | 부족한 근거와 요청 사항 명시 | `CASE_EVIDENCE_REQUESTED` / 부족 항목 | 422, IN_REVIEW 유지 |
| `NEEDS_EVIDENCE` → `IN_REVIEW` | REVIEWER | 추가 근거/불가 사유를 기록, 새 근거는 별도 revision | `CASE_REVIEW_RESUMED` / 출처·사유 | 409/422, NEEDS_EVIDENCE 유지 |
| `IN_REVIEW` → `READY_FOR_APPROVAL` | REVIEWER | 필수 출처·검토 완료, 불확실성 표시, 스냅샷 생성 가능 | `CASE_READY_FOR_APPROVAL` / 검토자·근거 revision | 422, IN_REVIEW 유지 |
| `IN_REVIEW` → `DISMISSED` | REVIEWER | 기각 이유와 근거 작성; 분석 실패만으로 기각 금지 | `CASE_DISMISSED` / 사유 | 422, IN_REVIEW 유지 |
| `READY_FOR_APPROVAL` → `IN_REVIEW` / `NEEDS_EVIDENCE` | 권한 있는 REVIEWER | 활성 PENDING 요청 없음, 필수 사유·`expectedCaseVersion`. `NEEDS_EVIDENCE`에는 부족 근거와 요청 사항 명시. snapshot과 기존 DRAFT는 불변으로 보존 | `CASE_RETURNED_TO_REVIEW` 또는 `CASE_EVIDENCE_REQUESTED` / 행위자·사유·전후 버전 | PENDING이면 409 `APPROVAL_REQUEST_ALREADY_PENDING`; 버전 충돌이면 409, 상태 유지 |
| `READY_FOR_APPROVAL` → `APPROVED_FOR_REPORT` | 시스템, APPROVER 결정에 의해 | PENDING 요청, 활성 발급 보류 없음, 자기 승인 아님, 유효한 1회용 서버 영수증, 동일 snapshot·manifest·ReportVersion 내용 digest/템플릿/revision, 결정/감사 원자 기록 | `APPROVAL_DECIDED` + `CASE_APPROVED_FOR_REPORT` / 결정·snapshot·ReportVersion ID·영수증 서버 ID | 409 증거/내용/보류/상태 충돌; 모두 롤백 |
| `READY_FOR_APPROVAL` → `IN_REVIEW` | REVIEWER 철회, APPROVER 반려, 또는 시스템 근거 변경 | PENDING 요청이 있으면 Case 전이 API로 우회하지 않는다. 철회/반려/무효화 명령이 먼저 Request와 ReportVersion을 종료하고 Case 복귀를 같은 트랜잭션에서 처리 | `CASE_RETURNED_TO_REVIEW` / 원인·요청 ID | 감사 기록 실패 시 전체 롤백 |
| `APPROVED_FOR_REPORT` → `IN_REVIEW` | 시스템, 관련 근거 V2 반영 | `evidenceRevision` 증가와 현재 승인 적격성 해제 원자 처리 | `APPROVAL_BASIS_STALE` + `CASE_RETURNED_TO_REVIEW` / 이전·새 hash | 갱신 실패 시 V2를 활성 근거로 공개하지 않음 |
| `APPROVED_FOR_REPORT` → `IN_REVIEW` | 시스템, 새 ReportVersion 내용 revision 생성 | 현재 reportVersion ID 교체·`caseVersion` 증가와 승인 적격성 해제 원자 처리. Evidence Revision은 그대로 | `REPORT_VERSION_DRAFTED` + `CASE_RETURNED_TO_REVIEW` / 이전·새 report digest | 실패 시 새 내용을 현재 발급 대상으로 공개하지 않음 |
| `APPROVED_FOR_REPORT` → `CLOSED` (`GENERATED_REPORT`) | REVIEWER | 현재 ReportVersion이 공식 공개된 `GENERATED`, `closureReportVersionId` 일치, 승인 바인딩 유효, 활성 발급 보류·렌더 예약 없음, 종료 사유·`expectedCaseVersion` | `CASE_CLOSED` / 생성 PDF ID·종료 사유·버전 | 미생성 409 `REPORT_NOT_GENERATED`, 보류/예약 409; 종료 롤백 |
| `APPROVED_FOR_REPORT` → `CLOSED` (`ISSUANCE_ABANDONED`) | REVIEWER | 현재 ReportVersion은 `GENERATED`가 아니며 렌더 예약 없음, 활성 보류 해결, 별도 `issuanceAbandonmentReason`과 종료 사유·`expectedCaseVersion` | `REPORT_ISSUANCE_ABANDONED` + `CASE_CLOSED` / 미발급 사실·사유·행위자·시각 | 생성/예약/보류 경합 시 409; 발급 완료로 표시하지 않음 |
| `DISMISSED` → `CLOSED` (`NOT_APPLICABLE`) | REVIEWER | 기각 확인, 종료 사유·`expectedCaseVersion`; 보고서 발급 경로 없음 | `CASE_CLOSED` / 기각 결정 참조·미발급 | 409/422, DISMISSED 유지 |

MVP에서 `DISMISSED`와 `CLOSED`는 기존 사건을 직접 재개하는 진입점이 아니다. 중요한 새 근거가 생기면 원 사건을 참조하는 별도 후속 Case를 `OPEN`으로 만든다. 기존 Case의 종료·승인·보고서·감사 이력은 그대로 둔다. `POST /cases/{caseId}/transitions`에는 `REOPEN`도 `APPROVED_FOR_REPORT` 직접 선택지도 없다. Specs `04`의 명시적 `REOPEN` 제안과 다르므로 DEC-12 동기화가 필요하다. 배정 변경은 상태 전이와 별도 명령이며 actor/이전·새 담당자/사유/버전을 CaseActivity에 남긴다. 승인 중 배정·REVIEWER 변경은 증거 revision을 자동 증가시키지 않지만 요청자의 현재 권한·담당 범위와 자기 승인 가드를 재평가하고 결과를 감사한다. 유지가 불가하면 PENDING 요청을 `INVALIDATED` 처리하고 새 revision으로 다시 제출한다.

`READY_FOR_APPROVAL`에 PENDING 요청이 없으면 REVIEWER가 전이 API로 `IN_REVIEW` 또는 `NEEDS_EVIDENCE`를 선택한다. PENDING이면 직접 전이는 409이고, REVIEWER의 `/approval-requests/{id}/withdraw`, APPROVER의 결정, 또는 시스템 무효화가 먼저 Request·ReportVersion을 종료하면서 Case를 `IN_REVIEW`로 되돌린다. 그 다음 `NEEDS_EVIDENCE`가 필요하면 새로운 `expectedCaseVersion`으로 별도 전이한다. `CLOSED`에서는 신규 렌더·`GENERATION_FAILED` 재시도를 금지하지만 기존 `GENERATED`/`SUPERSEDED` 파일의 통제된 재다운로드는 허용한다. 종료와 렌더 예약은 동일 Case/ReportVersion 직렬화 경계에서 경쟁하며 한쪽만 성공한다.

## 지속적인 발급 보류

`IssuanceHold`는 Case 상태나 Evidence Revision이 아니다. 권한 있는 REVIEWER 또는 신뢰된 서버 이벤트가 동일 tenant/event/Case에 `holdId`, 사유 코드, 행위자·시각, 당시 `evidenceRevision`, 생성 감사 ID를 가진 `ACTIVE` 기록을 추가하고 미소비 검토 영수증을 폐기한다. 여러 보류가 있으면 **모두** 해결될 때까지 발급과 새 승인 결정을 막는다. 단순 UI 표시 변경이나 재시도는 보류를 해제하지 않는다. 무관성이 확인되면 권한 있는 REVIEWER가 검토 근거·해제 사유·시각·관련 revision을 기록하고 `RELEASED`로 전이하며 해제 감사 이벤트를 남긴다. 이 경우에도 APPROVER는 새 검토 패키지/영수증을 받아야 한다. 관련성이 확인되면 근거 revision·snapshot을 갱신하고 PENDING 요청을 무효화하거나 과거 승인 적격성을 `STALE`로 만든 뒤에만 보류를 해제한다. 결정·발급 예약·공개 직전 검사는 서버 저장 ACTIVE 보류를 동일 직렬화 경계에서 다시 읽는다. 보류 등록이 먼저면 409 `ISSUANCE_HOLD_ACTIVE`; 발급 공개가 먼저면 기존 PDF 이력은 보존하고 보류는 이후 신규 발급에 적용한다.

## ApprovalRequest 전이 및 승인 결정

| 이전 → 목표 | 실행 주체 | 사전 조건 | 이벤트 / 감사 | 실패 시 |
|---|---|---|---|---|
| 없음 → `PENDING` | 권한 있는 REVIEWER | Case `READY_FOR_APPROVAL`, 같은 Case의 봉인된 `DRAFT` ReportVersion·snapshot·내용 digest, 같은 revision 활성 PENDING 없음, `expectedCaseVersion`·`expectedReportVersion`·멱등성 키 일치 | `APPROVAL_REQUESTED` / 요청자·snapshot hash·ReportVersion ID/digest; ReportVersion `PENDING_APPROVAL` | 동일 키·동일 body는 기존 응답; 다른 body 또는 별도 활성 요청은 409 |
| `PENDING` → `APPROVED` | 독립 APPROVER | 다른 사용자, 허용 공연, ACTIVE 보류 없음, 서버 발행·미소비·미만료 검토 영수증·snapshot·보고서 digest 일치, Case/Request/ReportVersion 버전 일치 | `APPROVAL_DECIDED` / 영수증 소비 + 불변 Decision(서버 영수증 ID) + ReportVersion `APPROVED` + Case 전이 | 경합 패자는 409, Decision 없음 |
| `PENDING` → `REJECTED` | 독립 APPROVER | 위와 동일, 구체적 반려 사유 | `APPROVAL_DECIDED` / 영수증 소비 + Decision + ReportVersion `REJECTED` + Case 복귀 | 장애는 REJECTED로 기록하지 않음 |
| `PENDING` → `WITHDRAWN` | 현재도 권한 있는 요청자 또는 해당 Case의 REVIEWER | 아직 결정 전, 사유·기대 Case/Request/ReportVersion 버전·멱등성 키 | `APPROVAL_WITHDRAWN` / 철회자·시간·사유·감사 ID, ReportVersion `WITHDRAWN`, Case 복귀 | 결정/철회 경합 패자는 409; 동일 키·동일 body 재시도는 기존 응답 |
| `PENDING` → `INVALIDATED` | 시스템 | 관련 근거·보고서 내용 변경 또는 요청자 권한 재평가 실패 | `APPROVAL_REQUEST_INVALIDATED` / 원인·변경 전후 revision, ReportVersion `INVALIDATED`, Case 복귀 | 변경과 무효화 중 하나라도 실패하면 둘 다 롤백 |

`APPROVED`/`REJECTED`/`WITHDRAWN`/`INVALIDATED` 요청과 기존 Decision은 terminal이다. 철회는 승인·반려 이후 불가하다. `PENDING` 요청자가 배정/REVIEWER 변경으로 권한을 잃으면 결정 전에 원자적으로 무효화한다. 단순 배정 변경만으로 증거 revision은 증가하지 않는다. 승인 후 근거가 변하면 과거 Request/Decision을 고치지 않고 `approvalEligibility=STALE`을 계산하며 `APPROVAL_BASIS_STALE` 이벤트를 남긴다. 새 근거 V2 또는 보고서 내용 변경에는 새 ReportVersion revision·요청·결정이 필요하다. 철회 명령은 `POST /api/v1/approval-requests/{approvalRequestId}/withdraw`와 동일한 상태 전이를 사용한다.

## ReportVersion 상태와 단일 결정 경로

| 이전 → 목표 | 원인·가드 |
|---|---|
| 없음 → `DRAFT` | REVIEWER가 현재 스냅샷으로 새 revision과 봉인된 내용 digest·템플릿 버전을 생성. `currentReportVersionId`·`caseVersion`을 갱신하고 이전 승인이 있으면 현재 적격성을 `STALE`로 만든다. 내용 제자리 수정 불가. |
| `DRAFT` → `PENDING_APPROVAL` | REVIEWER가 새 ApprovalRequest 제출. 같은 revision의 활성 요청은 최대 1개. |
| `PENDING_APPROVAL` → `APPROVED` / `REJECTED` | 오직 `POST /approval-requests/{approvalRequestId}/decision`으로 APPROVER 결정. Case·Request·ReportVersion·감사 원자 처리. |
| `PENDING_APPROVAL` → `WITHDRAWN` / `INVALIDATED` | 철회 또는 시스템 무효화. 두 상태는 terminal이며 재제출에는 새 revision 사용. Specs ReportVersion 상태도에 없는 추가 상태로 동기화 필요. |
| `APPROVED` → `RENDERING` → `GENERATED` / `GENERATION_FAILED` | Case `APPROVED_FOR_REPORT`, ACTIVE 보류·종료·중복 예약 없음과 승인 바인딩을 검사해 발급 예약. 봉인 입력으로 렌더하고 공개 직전 같은 직렬화 경계에서 재검사. 공식 `GENERATED` 공개가 성공한 때 이전 `GENERATED`를 `SUPERSEDED`로 바꾼다. 기술적 생성 실패만 `GENERATION_FAILED`. |
| `RENDERING` → `APPROVED` | 렌더 중 보류·V2·종료 경합으로 공개가 차단되면 비공개 임시 PDF를 폐기/격리하고 예약을 해제하며 `REPORT_PUBLICATION_BLOCKED`를 감사한다. 기존 승인 결정은 유지하지만 현재 적격성/보류 게이트가 통과하기 전 재예약 불가. 발급 완료로 기록하지 않는다. |
| `GENERATION_FAILED` → `RENDERING` | 동일 봉인 입력·템플릿·snapshot의 제한된 재시도만 허용. 현재 승인 적격성 재확인. |
| `GENERATED` → `SUPERSEDED` | 새 ReportVersion의 PDF가 공식 저장·공개되어 `GENERATED`가 되는 같은 최종화 트랜잭션에서만 이전 생성본을 역사적 버전으로 표시. 새 DRAFT/승인 또는 `STALE`만으로 이 전이를 실행하지 않는다. 기존 PDF·결정은 유지하고 동일 파일 재다운로드만 허용. |

Specs `07`의 `/report-versions/{id}/approve`는 독립 승인 쓰기 경로로 활성화하지 않는다. 새 결정 경로 한 곳에서만 Request/Decision과 ReportVersion을 변경한다. Specs 제안 API의 폐기·전환은 공유 문서 동기화 작업으로 남긴다.

## 역할별 권한

아래는 Specs `03_역할권한정의.md`와 차이가 있는 **Backend MVP 제안**이다. 모든 `O`에는 `authenticated && tenant membership && resource.tenantId == activeTenant && eventScope && stateGuard`가 추가된다. 동일 tenant라도 공연 범위 밖이면 자원 존재를 숨기는 404를 제안한다.

| 행위 | TENANT_ADMIN | ANALYST | REVIEWER | APPROVER | AUDITOR | REPORTER / PLATFORM_OPERATOR |
|---|---|---|---|---|---|---|
| Case 목록·상세 | O | 담당 범위 | O | 승인 범위 | 읽기 | - / - |
| 출처·증거 메타 조회 | O | 담당 범위 | O | 승인 범위 | 제한 읽기 | - / - |
| 원본 파일 열람 | 정책 승인 범위 | 담당 범위 | O | 검토 대상 | 제한 읽기 | 본인 접수 범위 / - |
| Case 생성·출처 연결 | - | O | O | - | - | - / - |
| Case 분류·상태 | - | - | O | 승인 결정으로만 | - | - / - |
| Case 담당자 배정 | O | - | O | - | - | - / - |
| 보고서 초안 생성 | - | - | O | - | - | - / - |
| 발급 보류 등록·해제 | - | - | O / O, 근거 확인 후 | - | - | - / - |
| 승인 요청 제출 | - | - (REVIEWER 역할도 있으면 그 역할로만) | O, 사건 검토 범위 | - | - | - / - |
| PENDING 요청 철회 | - | - (REVIEWER 역할도 있으면 그 역할로만) | 현재 권한 있는 요청자 또는 사건 REVIEWER | - | - | - / - |
| 승인/반려 결정 | - | - | - | O, 자기 승인 금지 | - | - / - |
| CaseActivity 조회 | O | 담당 범위 | O | 관련 범위 | O | - / - |
| ApprovalAuditEvent 조회 | O | - | - (자신의 요청 결과는 별도 조회) | 관련 범위 | O | - / - |
| 공식 보고서 발급 | 보류 | 보류 | 보류 | 보류 | - | - / - |

Specs `03`은 ANALYST와 REVIEWER를 별도 역할로 정의하고 `12`는 REVIEWER 제출을 기술한다. ANALYST의 단독 요청 권한은 MVP에서 부여하지 않는다. Specs `03`은 TENANT_ADMIN의 사건 상태 변경, TENANT_ADMIN·APPROVER의 초안 생성과 더 넓은 감사 조회를 제안하지만 Backend MVP는 기본 부여하지 않는다. TENANT_ADMIN은 멤버십·운영 범위를 관리하며 예외 개입은 일반 Case 전이/초안 API가 아닌 별도 승인·사유·감사 절차로 후속 설계한다. APPROVER는 독립 결정에 집중한다. 이 차이는 공유 Specs 동기화 대상이며 아직 최종 승인된 권한 변경이 아니다. 다중 역할 사용자는 REVIEWER 자격·사건 범위로만 요청하고 자신이 요청한 건을 APPROVER 역할로 자기 승인할 수 없다. 프런트의 버튼 숨김은 권한 검증이 아니다. API·서비스·Repository·파일 접근마다 동일한 tenant/event 범위를 강제한다. 인증/격리 미구현인 현재 Backend에서는 이 API를 다중 고객에게 제공하지 않는다.
