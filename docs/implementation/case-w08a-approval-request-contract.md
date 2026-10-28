# W08a 승인 요청 대상 고정 계약

## 범위와 안전 경계

W08a는 검토할 증거와 보고서 버전을 `PENDING` 승인 요청으로 고정하고 조회하는 기반만 구현한다.
승인 또는 반려 결정, 철회와 무효화 명령, ReviewReceipt, 공식 PDF 생성과 발급은 W08b와 W09 범위다.
요청 응답의 `approvalEligibility`는 항상 `BLOCKED`이며 기존 공식 PDF 엔드포인트의 HTTP 409 차단도 유지한다.
`READY_FOR_APPROVAL`과 `PENDING_APPROVAL`은 검토 대상이 고정됐다는 뜻이지 승인 또는 발급 적격 상태가 아니다.

구현 API는 `docs/api/case-w08a-v1.yaml`의 다음 두 경로뿐이다.

- `POST /api/v1/cases/{caseId}/approval-requests`
- `GET /api/v1/approval-requests/{approvalRequestId}`

전체 승인, 결정, 영수증과 발급 흐름을 담은 `case-approval-v1-proposal.yaml`은 계속
`PROPOSED_UNIMPLEMENTED`다.

## 기존 구현 감사 결과

- W04의 `READY_FOR_APPROVAL`은 enum에만 존재했고 실제 전이는 없었다.
- Case에는 현재 보고서 포인터가 없었다.
- W05 `ReportVersion.revision`과 JPA `@Version version`은 서로 다른 값이지만 상태는 `DRAFT`뿐이었다.
- 기존 `draft-input-v1`은 실제 보관된 템플릿 아티팩트가 아니라 논리 입력 스키마 이름이었다.
- W06 `approvalEligibility`는 의도적으로 `BLOCKED`이며 W07 보존 성공만으로 변경되지 않는다.
- W06 검증과 W07 보존 기록은 DB 관계를 갖지만 승인 요청 제출 시 최신 유효 기록을 결합하는 경로는 없었다.
- Case 멱등 기록, 비관적 Case 잠금, CaseActivity와 한 트랜잭션으로 저장하는 기존 패턴은 재사용할 수 있다.
- IN_REVIEW에서만 새 보고서 revision을 만들 수 있으므로 제출 후 새 revision 생성은 상태 게이트로 차단할 수 있다.

## 보고서와 템플릿 고정

새 보고서 revision은 클래스패스의
`report-templates/case-approval-input-v1.json`을 사용한다. 서버는 이 아티팩트의 정규화된 UTF-8
바이트 SHA-256을 계산해 `template_version`과 `template_sha256`을 보고서에 함께 저장한다. 제출 시
레지스트리의 현재 아티팩트와 두 값을 다시 비교한다. 기존 `draft-input-v1` 행은 마이그레이션에서
`template_sha256=NULL`로 남기며 자동 승격하지 않는다. 따라서 실제 템플릿 근거가 없는 레거시 초안은
`TEMPLATE_NOT_RETAINED`로 차단된다.

`ReportVersion`의 Hibernate `@Immutable`은 제거했지만 내용 변경을 허용한 것은 아니다. ID, tenant,
event, Case, snapshot, evidence revision, report revision, template 버전과 digest, content JSON과 digest,
작성자와 작성 시각은 모두 JPA `updatable=false`다. 변경 가능한 필드는 상태와 optimistic lock version뿐이다.
W08a는 `DRAFT -> PENDING_APPROVAL`만 수행한다. DB 직접 쓰기 권한은 이 ORM 제한을 우회할 수 있으므로
운영에서는 DB 권한 분리와 변경 감사를 별도로 유지해야 한다.

제출 트랜잭션은 저장된 `manifest_json`과 `content_json`에서 SHA-256을 다시 계산한다. 보고서 JSON은
snapshot ID, manifest digest와 템플릿 버전을 포함해야 한다. 클라이언트의 expected digest는 비교 입력일
뿐이며 저장할 진실의 원천이 아니다.

## 제출 게이트

POST는 인증된 `REVIEWER`만 실행한다. actor, tenant와 event는 서버 membership과 Case에서 결정한다.
다른 tenant 또는 접근할 수 없는 event의 식별자는 404로 숨긴다. 외부 객체 저장소 호출은 수행하지 않고,
현재 DB에 저장된 검증 증명을 다음과 같이 fail-closed로 다시 결합한다.

1. Case는 `IN_REVIEW`이고 요청의 Case version과 일치한다.
2. 요청 보고서는 해당 Case의 최신 `DRAFT`이며 report optimistic version이 일치한다.
3. snapshot과 보고서가 같은 tenant, event, Case 및 현재 evidence revision에 속한다.
4. snapshot manifest와 보고서 내용 digest를 서버가 다시 계산했을 때 저장값 및 요청값과 일치한다.
5. 등록된 템플릿 버전과 실제 템플릿 digest가 일치한다.
6. 각 `EVIDENCE_ASSET` source의 최신 W06 기록이 동일 snapshot/source/asset에 대한 `MATCH`다.
7. 그 W06 기록을 참조하는 최신 W07 기록이 `IMMUTABLE_VERIFIED`이고 실제 version ID와 유효한
   retain-until을 가진다.
8. W07 기록의 정확한 policy version이 현재 최신 버전이며 여전히 `APPROVED`, 적용 기간과 tenant/event,
   storage profile 및 lock mode가 일치한다.
9. 원본 EvidenceAsset retention이 만료되지 않았고 W07 retain-until이 원본 retention을 넘지 않는다.
10. 동일 Case에 `ACTIVE` IssuanceHold 또는 활성 PENDING 요청이 없다.

파일 없는 metadata source는 W05 manifest DB 재검증 대상으로 남는다. 파일이 있는데 W06 또는 W07 근거가
없거나 정책이 철회 또는 교체됐으면 `APPROVAL_BASIS_BLOCKED`다. 이 게이트는 승인 요청 저장 조건일 뿐,
독립 APPROVER 결정이나 개인정보 보존 정책의 법률적 승인을 대신하지 않는다.

## 원자성, 멱등성과 경쟁 처리

검증 직전에 Case를 비관적 쓰기 잠금으로 다시 읽고 권한과 모든 DB 근거를 재검증한다. 같은 트랜잭션에서
다음을 커밋한다.

- Case `IN_REVIEW -> READY_FOR_APPROVAL` 및 `current_report_version_id`
- ReportVersion `DRAFT -> PENDING_APPROVAL`
- immutable binding을 가진 `ApprovalRequest(PENDING)`
- `APPROVAL_REQUESTED` CaseActivity와 append-only ApprovalAuditEvent
- `APPROVAL_REQUEST_CREATE` CaseIdempotencyRecord

어느 INSERT 또는 상태 저장이라도 실패하면 전체가 롤백된다. Object Lock 확인은 이미 W07에서 끝난
증명을 읽으므로 이 DB 쓰기 트랜잭션 안에서 외부 S3 호출을 하지 않는다.

동일 actor, Case와 Idempotency-Key의 동일 본문은 저장된 요청을 재생한다. 같은 키의 다른 본문은
`IDEMPOTENCY_KEY_REUSED`다. 보고서별 UNIQUE는 한 revision의 재제출을 막는다. MySQL 8에는 PostgreSQL식
partial unique index가 없으므로 `active_case_id` 슬롯을 사용한다. PENDING 행은 이 값이 `case_id`여야
하고 종료 상태 행은 NULL이어야 한다는 CHECK와 `(tenant_id, active_case_id)` UNIQUE를 결합한다. 따라서
애플리케이션 사전 조회와 별개로 한 Case의 활성 PENDING은 DB에서도 하나뿐이다.

새 report revision과 제출이 경쟁하면 Case 잠금과 optimistic version 때문에 한쪽만 커밋한다. 감사
INSERT 실패도 Case, report, request, activity와 idempotency 결과를 함께 롤백한다.

## 영속 모델과 마이그레이션

`docs/database/case-w08a-migration.sql`은 W07 이후 적용하는 additive migration이다.

- `report_versions.template_sha256`과 상태 CHECK 확장
- `cases.current_report_version_id` 및 tenant/event/Case 범위 FK
- `approval_requests`와 report 단일 요청, 활성 Case 슬롯 UNIQUE 및 범위 FK/CHECK
- append-only `approval_audit_events`
- `issuance_holds`와 ACTIVE/RELEASED shape CHECK

적용 순서는 W02, W04, W05, W06, W07, W08a다. 기존 행은 승인 요청 또는 승인 완료로 자동 변환하지
않는다. `IssuanceHold` 생성/해제 운영 API는 이번 범위가 아니며, 저장 모델과 ACTIVE 조회 게이트만
제공한다. 후속 보류 명령은 승인 요청과 동일한 Case 잠금을 사용해야 한다. 현재 애플리케이션에는 보류를
동시에 생성하는 쓰기 경로가 없으며, 권한 있는 DB 직접 INSERT와의 경쟁까지 ORM이 차단하지는 못한다.

## 조회와 개인정보 경계

GET은 현행 역할 중 `TENANT_ADMIN`, `REVIEWER`, `APPROVER`만 허용한다. 제안 계약에 있던 `AUDITOR`는
현재 역할 enum과 DB에 존재하지 않아 구현 역할로 추가하지 않았다. 응답은 고정된 ID, revision, digest,
요청자와 상태만 반환하며 object key, 이미지 바이트, 보존 저장소 자격증명 또는 원본 증거 내용은 반환하지
않는다. POST와 GET 응답은 `Cache-Control: no-store`다.

## 검증 경계와 후속 작업

기본 통합 테스트는 인증, IDOR, digest 위조, 레거시 또는 변조된 템플릿, W06/W07 근거 부족, 철회된
정책, ACTIVE hold, 멱등 재생, 중복 및 롤백을 검증한다. 격리 MySQL 8 테스트는 additive migration,
`ddl-auto=validate`, FK/UNIQUE/CHECK, 동일 키와 다른 키의 경쟁, report revision 경쟁 및 감사 INSERT
롤백을 검증한다. 격리 MinIO 테스트는 실제 Governance Object Lock version과 MySQL W07 보존 기록을
만든 뒤 그 증명으로 W08a 요청이 고정되는 경로를 검증한다. 필수 환경 테스트가 기본 빌드에서 조건부
skip되면 `.github/scripts/check_junit_results.py`가 정확한 skip 집합을 요구하며 각 CI job에서는 실제
실행 수와 zero-skip을 요구한다.

W08b 전에는 APPROVED 또는 REJECTED 상태 전이, 자기 승인 방지와 ReviewReceipt가 없다. W09 전에는
공식 PDF 아티팩트와 발급이 없다. 운영 보존 정책의 법률 및 고객사 승인이 확정되지 않은 동안 이 구현을
공식 승인 또는 개인정보 장기 보존 승인으로 해석해서는 안 된다.
