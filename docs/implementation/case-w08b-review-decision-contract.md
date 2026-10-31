# W08b 승인 검토 영수증과 결정 계약

## 범위와 경계

W08b는 W08a의 `PENDING` 승인 요청을 독립된 `APPROVER`가 검토하고 `APPROVE` 또는
`REJECT` 결정으로 닫는 기반이다. `REVIEWER`는 아직 결정되지 않은 요청을 철회할 수 있다.
이 작업은 공식 PDF 생성 또는 발급, 자동 부정 거래 확정, 티켓 취소와 제재를 구현하지 않는다.
기존 공식 PDF 엔드포인트의 HTTP 409 차단과 W06 응답의 `approvalEligibility=BLOCKED`는 유지한다.

`case-approval-v1-proposal.yaml`은 제안 문서다. 실제 W08b API 계약은
`docs/api/case-w08b-v1.yaml`이며, 구현한 네 경로만 다룬다.

## 상태와 대상 고정

W08a가 이미 다음 값을 고정한다.

- `ApprovalRequest`: tenant, event, Case, report revision, report content와 template digest,
  snapshot, evidence revision, manifest digest, 요청자 membership
- `ReportVersion`: 봉인된 content, template, revision 필드는 `updatable=false`이고 상태만 전이한다.
- `EvidenceSnapshot`: 봉인된 manifest와 evidence revision

W08b는 각 명령 전에 server-side digest를 다시 계산하고 현재 Case의
`READY_FOR_APPROVAL`, `currentReportVersionId`, report의 `PENDING_APPROVAL`, Request의
`PENDING`을 모두 확인한다. 템플릿 registry에서 동일한 artifact version과 SHA-256을 확인할 수
없으면 `TEMPLATE_NOT_RETAINED`로 차단한다. 기존 `draft-input-v1` 보고서는 자동 승격하지 않는다.

상태 전이는 다음과 같다.

| 명령 | 요청 | 보고서 | Case | 영수증 |
| --- | --- | --- | --- | --- |
| APPROVE | PENDING -> APPROVED | PENDING_APPROVAL -> APPROVED | READY_FOR_APPROVAL -> APPROVED_FOR_REPORT | 소비 |
| REJECT | PENDING -> REJECTED | PENDING_APPROVAL -> REJECTED | READY_FOR_APPROVAL -> IN_REVIEW | 소비 |
| WITHDRAW | PENDING -> WITHDRAWN | PENDING_APPROVAL -> WITHDRAWN | READY_FOR_APPROVAL -> IN_REVIEW | 미소비분 취소 |
| trusted invalidation | PENDING -> INVALIDATED | PENDING_APPROVAL -> INVALIDATED | READY_FOR_APPROVAL -> IN_REVIEW | 미소비분 취소 |

시스템 무효화는 외부 API가 아니다. `ApprovalReviewWriteService.invalidateFromTrustedSystem()`은
활성 멤버십과 tenant/event grant가 확인된 서버 actor만 감사 주체로 사용하고, PENDING lifecycle과
소유 범위만 검증한 뒤 상태를 `INVALIDATED`로 닫는다. 변경된 근거를 승인 가능한 것으로 처리하지 않는다.
자동 연결 범위와 한계는 아래 "승인 대기 요청 자동 무효화(#47)"에 정리한다.

## 검토 패키지와 영수증

`GET /api/v1/approval-requests/{id}/review-package`은 `APPROVER`에게만 봉인된 report
content와 제한된 snapshot metadata를 반환한다. GET은 상태를 바꾸지 않고 영수증도 만들지 않는다.
응답에는 원본 객체 key, 원본 바이트, 저장소 자격증명, 임의 다운로드 URL이 없다.

`POST` 같은 경로는 32 바이트 `SecureRandom` 값을 URL-safe Base64로 만든 일회용 토큰을 한 번만
반환한다. DB, 감사, 활동 및 멱등 기록에는 원문 대신 SHA-256만 남긴다. TTL은
`ticket-defender.approval.review-receipt-ttl` 설정으로 결정되고 기술 기본값은 `PT15M`이다.
이는 법적 또는 운영상 확정 보존 기간이 아니다.

영수증은 approver membership, tenant/event/Case, Request, report revision과 digest, snapshot
revision과 manifest digest에 바인딩된다. 같은 approver가 재발급하면 이전 `ISSUED` 영수증을 같은
트랜잭션에서 `REVOKED` 또는 이미 만료된 경우 `EXPIRED`로 바꾼다. 토큰은 다른 요청자나 다른
승인자가 사용할 수 없다.

## 권한, 독립성, 증거 게이트

모든 경로는 JWT `subject`에서 tenant membership과 event grant를 찾는다. 범위 밖 Request는
`RESOURCE_NOT_FOUND`로 숨긴다. `APPROVER` 결정 직전 요청자 membership과 승인자 membership의
`subject`를 비교하므로 한 사용자에게 역할이나 membership 표현이 여러 개여도 자기 승인을 우회할 수
없다.

영수증 발급과 결정은 W08a와 같은 `ApprovalRequestBasisValidator`를 다시 실행한다. 각 파일 근거는
현재 MATCH 무결성 기록, `IMMUTABLE_VERIFIED` W07 보존 기록, 현재 유효한 APPROVED 보존 정책과
원본 retention을 만족해야 한다. `ACTIVE` `IssuanceHold`도 발급과 결정을 차단한다.

이 검사는 W08a 요청 저장 조건 및 W08b 사람의 결정 조건을 보강할 뿐 W09 발급 적격성을 만들지
않는다. 따라서 결정 응답도 `issuanceEligibility=BLOCKED`를 반환한다.

현재 IssuanceHold 생성과 해제 운영 API는 없다. W08b는 Case write lock 안에서 현재 ACTIVE hold를
검사하지만, 미래의 hold writer가 같은 Case 직렬화 경계에 참여하지 않으면 신규 hold와 결정의
완전한 경합 차단을 주장할 수 없다. 그 writer와 운영 워크플로는 후속 작업에서 같은 Case lock을
사용하도록 구현해야 한다.

## 원자성, 멱등성과 감사

결정과 철회는 Case pessimistic lock 아래의 짧은 DB 트랜잭션이다. 상태 전이, 영수증 소비 또는
취소, `ApprovalDecision`, `CaseActivity`, append-only `ApprovalAuditEvent`, 그리고 멱등 결과를
모두 저장하거나 모두 롤백한다. Request별 `approval_decisions` UNIQUE와 receipt별 UNIQUE가 DB에서도
두 번째 결정을 거절한다.

결정과 철회는 `Idempotency-Key`를 요구한다. 동일 actor, Case, operation, key와 동일한 fingerprint는
기존 결과를 재생하고, 다른 body는 `IDEMPOTENCY_KEY_REUSED`다. 결정 fingerprint와 저장 결과에는 raw
review receipt token이 포함되지 않고 해당 SHA-256만 포함된다. 인증 또는 권한을 다시 확인한 뒤에만
재생하므로 권한 철회 후 재전송은 이전 결과를 노출하지 않는다.

`approval_audit_events`는 기존 행을 변경하지 않는다. W08b migration은 receipt/decision 참조와
`REVIEW_RECEIPT_ISSUED`, `REVIEW_RECEIPT_REVOKED`, `REVIEW_RECEIPT_EXPIRED`,
`APPROVAL_DECIDED`, `APPROVAL_WITHDRAWN`, `APPROVAL_INVALIDATED` 이벤트를 additive하게 허용한다.

## 읽기 일관성과 잠금 경계

`issueReceipt`, `decide`, `withdraw`, `invalidateFromTrustedSystem`은 해당 짧은 쓰기
트랜잭션에만 `READ_COMMITTED`를 적용한다. 전역 datasource, Hikari, MySQL 기본 격리 수준은
바꾸지 않으며, `findCaseIdById` → Case `FOR UPDATE` → Request `FOR UPDATE` 순서도 유지한다.
이는 MySQL `REPEATABLE_READ`에서 잠금 대기 전 일반 조회가 만든 스냅샷 때문에, Case 잠금을
획득한 뒤 `ApprovalRequestBasisValidator`가 이미 커밋된 보존 정책이나 증거 변경을 과거 값으로
검증하는 일을 피하기 위함이다.

이 설정은 Case 잠금 획득 뒤 일반 검증 조회가 그 시점에 커밋된 값을 읽도록 한다. 다만 검증이
끝난 뒤 별도 정책 또는 `IssuanceHold` writer가 같은 Case 잠금 경계에 참여하지 않고 변경하는
경쟁까지 직렬화하지는 않는다. 향후 그러한 writer는 같은 Case write lock을 사용하거나 별도
업무상 동기화 규칙을 가져야 한다.

## MySQL 적용 순서

1. 기존 W02, W04, W05, W06, W07 및 W08a migration을 적용한다.
2. `docs/database/case-w08b-migration.sql`을 한 번 적용한다.
3. JPA는 `ddl-auto=validate`로 새 receipt와 decision 테이블을 검증한다.

W08b migration은 기존 PENDING 요청을 승인, 반려, 무효화하거나 영수증을 자동 생성하지 않는다.
`review_receipts`에는 token digest UNIQUE, scope FKs, 상태 shape CHECK와 approver별 활성 receipt UNIQUE가
있다. `approval_decisions`에는 Request 및 receipt별 UNIQUE와 scope FKs가 있다.

## 승인 대기 요청 자동 무효화(#47)

### 변경 경로 조사 결과

PENDING 요청이 있는 동안 Case는 `READY_FOR_APPROVAL`이며, 앱 안의 쓰기 경로는 다음과 같다.

| 변경 종류 | 앱 내 쓰기 경로 | PENDING 중 가능 여부 |
| --- | --- | --- |
| Case 상태 전이, Snapshot, 초안 | `CaseWriteService.transition`, `CasePreparationWriteService` | 불가(`IN_REVIEW` 등 필요) |
| W06 무결성 검증, W07 보존 기록 | `EvidenceIntegrityWriteService`, `EvidencePreservationWriteService` | 불가(`IN_REVIEW` 필요) |
| Case 담당자 변경 | `CaseWriteService.assign` | 가능. 승인 근거나 요청자 권한이 아니므로 무효화하지 않음 |
| 공식 PDF 발급 Hold | `InternalIssuanceHoldWriteService` | 가능. 일시적 차단이므로 무효화하지 않음 |
| 멤버십, 역할, 이벤트 grant | 없음(앱 밖 또는 DB 직접 변경) | 앱이 변경 시점을 알 수 없음 |
| 보존 정책 버전, 철회 | 없음(앱 밖 또는 DB 직접 변경) | 앱이 변경 시점을 알 수 없음 |
| FanReport, 증거 자산 상태, 보존기간 만료 | 접수 시에만 기록, 이후는 시간 경과 또는 DB 직접 변경 | 앱이 변경 시점을 알 수 없음 |

따라서 PENDING 근거를 바꾸는 앱 내 쓰기 경로는 없고, 실제 변경은 앱 밖 변경이나 시간 경과로만 생긴다.
이를 감지할 수 있는 지점은 승인 검토 영수증 발급(`issueReceipt`)과 결정(`decide`)의 서버 재검증이므로,
자동 무효화를 이 두 경로에 연결한다. 변경 시점에 즉시 감지하는 listener나 주기 점검 작업은 없다.

### 무효화 조건

`issueReceipt` 또는 `decide`가 아래 코드로 차단되면, 실패한 트랜잭션이 롤백된 뒤
`ApprovalInvalidationWriteService`가 별도 트랜잭션에서 Case FOR UPDATE, Request FOR UPDATE 순서로 잠그고
바인딩, 요청자 권한, 근거를 다시 판정한다. 같은 영구 변경이 확인될 때만 `invalidateFromTrustedSystem()`을
호출한다. 호출자가 넘긴 오류 코드만으로 무효화하지 않는다.

| 코드 | 의미 |
| --- | --- |
| `EVIDENCE_VERSION_MISMATCH` | Case, Snapshot, ReportVersion의 evidence revision이 요청과 다름 |
| `SOURCE_CHANGED` | 고정된 출처 manifest가 현재 DB 출처와 다름 |
| `SOURCE_NOT_AVAILABLE` | FanReport 또는 증거 자산이 더 이상 사용 가능 상태가 아니거나 보존기간 만료 |
| `APPROVAL_BASIS_BLOCKED` | W06 MATCH, W07 보존, 보존 정책 승인 및 유효기간, 증거 보존기간 중 하나가 깨짐 |
| `APPROVAL_REQUESTER_ACCESS_REVOKED` | 요청자 멤버십이 ACTIVE가 아니거나 REVIEWER 역할 또는 해당 event grant가 없음 |

`APPROVAL_REQUESTER_ACCESS_REVOKED`는 #47에서 추가한 검사이며, 요청자 권한이 철회되면 Receipt 발급과 결정이
HTTP 409로 차단된다.

다음은 무효화하지 않고 차단만 유지한다.
- 봉인된 행 자체가 바뀐 경우: `APPROVAL_REVIEW_MISMATCH`, `EVIDENCE_REVIEW_MISMATCH`, `REPORT_CONTENT_MISMATCH`,
  `TEMPLATE_NOT_RETAINED`. 조작 의심이므로 상태를 자동으로 바꾸지 않고 사람이 확인해야 한다.
- 일시적이거나 클라이언트 원인인 경우: `ISSUANCE_HOLD_ACTIVE`, Receipt 오류, 버전 충돌, 입력 불일치.

### 실행 주체, 감사, 실패 처리

- 실행 주체는 변경을 감지한 승인자 멤버십이다. 활동 기록은 `SYSTEM` 역할의 `APPROVAL_INVALIDATED`이며,
  사유는 `AUTOMATIC_INVALIDATION; code=<코드>`로 남는다. 승인 감사에도 같은 사유의
  `APPROVAL_INVALIDATED`가 남는다. 별도 시스템 계정은 정책 미정이라 만들지 않았다.
- 무효화가 실패하면 그 트랜잭션 전체가 롤백되어 Request, Case, ReportVersion, Receipt, 감사에 부분 변경이
  남지 않는다. API는 원래 차단 코드를 그대로 반환하고, 서버 로그에 요청 ID와 코드만 기록한다.
  다음 Receipt 발급이나 결정 시도에서 다시 판정한다. 실패 시도를 DB 감사 테이블에 남기는 기능은
  새 이벤트 종류와 migration이 필요해 구현하지 않았다.
- 이미 닫힌 요청(APPROVED, REJECTED, WITHDRAWN, INVALIDATED)은 판정하지 않으므로 중복 무효화가 없고,
  완료된 `ApprovalDecision`은 바뀌지 않는다. 승인 이후의 근거 변경은 W09의 발급 적격성 `STALE` 계산이
  다루며, 이 경로와 별개다.
- 무효화되면 미소비 Receipt는 모두 REVOKED가 되어 재사용할 수 없고, 활성 슬롯이 해제되어 Case는
  `IN_REVIEW`로 돌아간다.

### 연결되지 않은 경로

- 멤버십, 역할, grant, 보존 정책을 바꾸는 운영 API가 생기면, 그 변경 트랜잭션에서 영향받는 PENDING 요청을
  찾아 같은 판정 경로를 호출해야 한다. 현재는 승인자가 다음 Receipt 발급이나 결정을 시도할 때까지
  PENDING 상태가 유지된다.
- 검토 패키지 조회(`reviewPackage`)는 읽기 전용 GET이라 무효화하지 않는다.
- 아무도 승인을 시도하지 않는 요청의 시간 경과(보존기간 만료)를 찾아 닫는 주기 점검 작업은 없다.

## 후속 범위

W08b는 `ApprovalDecision`과 검토 흔적만 저장한다. W09의 공식 PDF 생성과 발급, ReviewReceipt를
외부에 증명하는 별도 문서, 운영 보존 정책의 법적 승인, hold 생성과 해제 workflow는 여전히 후속
범위다.

W09는 `APPROVED` 결정만으로 발급을 허용해서는 안 된다. W08b가 남긴 불변 입력은
`ApprovalDecision.id`, 승인된 `ReportVersion`의 ID/revision/content SHA-256, 당시
`EvidenceSnapshot` ID/manifest SHA-256, 그리고 소비된 `ReviewReceipt.id`다. W09는 이 값을 다시
읽고 현재 W06 MATCH, W07 보존/정책, retention, ACTIVE hold 및 별도 발급 정책을 재검증해야 한다.
W08b는 이후 근거 변경에 대한 발급 적격성 `STALE` 계산이나 PDF 템플릿 고정을 구현하지 않으며,
`approvalEligibility=BLOCKED`와 기존 HTTP 409 발급 차단은 그때까지 유지된다.
