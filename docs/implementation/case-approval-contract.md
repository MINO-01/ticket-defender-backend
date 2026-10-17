# Case·Approval 도메인 계약 초안 (Issue #20)

> **MVP 정책 반영 / PROPOSED / 문서 전용 / 미구현.** 사용자가 Issue #20 검토 후 선택한 정책을 이 Backend 초안에 반영했다. Specs 계약 레지스트리의 관리자 API는 여전히 `PROPOSED`이고 DEC-09·DEC-12는 `PENDING`이므로 공유 계약의 최종 승인으로 표시하지 않는다. 현재 `TicketAudit`를 Case로 재해석하거나 기존 `/api/v1/audits/**` 계약을 변경하지 않는다. [상태·권한](case-approval-transitions-permissions.md), [증거 정책](case-approval-evidence-policy.md), [OpenAPI 초안](../api/case-approval-v1-proposal.yaml), [Specs 동기화 작업](../issues/case-approval-decisions-followups.md)을 함께 검토한다.

## 확인한 현재 구현

| 현재 소스 | 확인한 동작 | 신규 계약과의 차이 |
|---|---|---|
| `TicketAudit`, `AuditStatus` | `Long id`, `NORMAL/FRAUD_DETECTED/REPORT_ISSUED/DUPLICATED`, 제보자·예매·분석/제보 근거 저장 | Case ID·상태·고객사·담당자·버전·승인 정보가 없다. `FRAUD_DETECTED`는 인간의 부정행위 확인이 아니다. |
| `MacroAuditEvidence`, `MacroAuditPersistenceService` | `analysisRequestId`, `clusterId`, 알고리즘/버전/완료시각을 감사 건에 저장. 공연·예매·계정 조합의 `evidenceKey`로 재저장 회피 | 분석 **실행** 엔티티, Case 연결, 군집 재계산 이력, 스냅샷은 없다. `clusterId`를 영속 Case ID로 쓸 수 없다. |
| `VlmAnalysisService`, `TicketAudit.createVlmAudit` | 이미 파싱된 좌석·점수·이미지 URL을 받아 예매를 대조하고 조건에 맞으면 `FAN_REPORT` 감사를 저장 | 공개 제보 접수/파일 업로드 Controller, 독립 `fanReportId`, 원본 파일 버전·무결성 기록은 확인되지 않는다. |
| `AuditController`, `FastApiAdapter`, `FraudAnalysisService` | `/api/v1/audits/analyze`가 FastAPI 분석을 호출. 장애는 `UNAVAILABLE`/503, 응답 계약 오류는 502. 완료된 군집만 저장 | 분석 완료는 Case 승인도 사기 확정도 아니다. 실패를 정상 0건으로 바꾸지 않는다. |
| `AuditReportService`, `AuditReportController` | `/api/v1/audits/{auditId}/report`는 서비스 첫 단계에서 무조건 발급 거절, 409 `AUDIT_REPORT_ISSUANCE_DISABLED` | 신규 승인 설계가 기존 차단을 해제하지 않는다. 차단 서비스·Controller 테스트가 존재한다. |
| `build.gradle`, 전체 `src/main/java`, `src/test` | Spring Security 의존성, 인증 문맥·tenant membership/격리 모델, Case/Approval 엔티티·API는 확인되지 않는다 | 아래 정책은 구현 선행 조건이며 다중 고객용 공개 API를 활성화할 수 없다. |

Specs 저장소의 `02`, `03`, `04`, `06`, `07`, `11`, `12`, `15`와 `contracts/admin-api-v1-proposal.yaml`, `contracts/case-workflow.mmd`, `contracts/registry.yaml`을 읽었다. Specs의 관리자 API와 상태도 역시 **PROPOSED**이며 레지스트리 승인·구현은 완료되지 않았다. 프런트엔드에서 합성한 Case 화면 모델은 이 Backend의 계약 근거가 아니다.

## 객체와 관계

| 객체 | 소유 책임 | 관계·불변식 |
|---|---|---|
| `Case` | 고객사와 공연에 속한 조사 단위의 영속 ID, `type`, 업무 상태, 담당자, `caseVersion`, `evidenceRevision`, 현재 `currentReportVersionId` 및 종료 시 `closureDisposition` 관리 | 하나의 Case에 여러 출처를 명시적으로 연결. 분석 결과 재계산이나 군집 재편으로 ID를 바꾸지 않는다. 후속 Case는 `relatedCaseId`와 `relationReason`으로 원 Case를 참조하며 승인·보고서를 승계하지 않는다. `hasActiveIssuanceHold`는 서버가 계산한 조회값이며 발급 게이트의 유일한 근거가 아니다. 자동 병합·자동 부정 판정 금지. |
| `CaseSourceReference` | 정확한 출처 종류/레코드/버전과 연결 사유 저장 | Case 1:N. `FAN_REPORT`의 `sourceRecordId=fanReportId`, `EVIDENCE_ASSET`의 `sourceRecordId=evidenceId`를 구분한다. 증거가 제보에 속하면 `fanReportId`도 보존한다. 서버가 제보·파일의 tenant/event·소유 관계를 확인한다. 군집은 `(tenantId, analysisRunId, clusterId)`로 식별한다. |
| `CaseActivity` | 배정·상태전이·출처 연결·검토 메모 등 시간순 업무 이력 | Case 1:N, append-only. 행위자, 시각, 전후 상태/버전, 사유, 요청 ID와 관련 감사 이벤트 ID를 기록한다. |
| `ApprovalRequest` | 한 Case의 **특정 ReportVersion revision과 EvidenceSnapshot**에 대한 검토 요청 | ReportVersion 1:0..1 Request. Case의 현재 발급 대상 활성 `PENDING`도 최대 1건. 반려·철회·무효화 후 재제출은 새 ReportVersion revision과 새 Request가 필요하다. 요청별 최대 한 Decision. |
| `ApprovalDecision` | 승인자 1인의 승인/반려 판단과 실제 본 스냅샷·보고서 내용 digest, 소비된 영수증의 서버 식별자 | Request 1:0..1. 서버 검증 후 생성, 이후 내용 수정·삭제 금지. 승인만으로 PDF가 발급되지는 않는다. |
| `ReviewReceipt` | 승인자에게 제공한 검토 패키지와 결정의 서버 측 연결 | 사용자·tenant·event·Case·Request·snapshot ID/hash·ReportVersion ID/digest·발급/만료 시각을 저장한다. 클라이언트에는 별도 고엔트로피 토큰만 전달하고 서버에는 토큰 digest를 저장한다. 원문 토큰은 감사에 쓰지 않는다. 결정당 1회 소비하며 결정/소비를 원자 처리한다. |
| `EvidenceSnapshot` | 판단에 사용될 근거·버전·해시의 불변 manifest | Case 1:N. 원본 파일, 선택된 추출/교정·대조, 분석 실행/알고리즘 버전, 조사 판단과 한계를 포함한다. 보고서 본문·템플릿의 버전은 ReportVersion에서 별도로 고정한다. 파일 바이트 자체를 공개 API에 넣지 않는다. |
| `ReportVersion` | 봉인된 보고서 내용·템플릿·증거 스냅샷과 PDF 생성 상태 | Case 1:N. `revision`은 내용 버전, `version`은 상태 변경용 optimistic lock. 요청 제출 시 내용 digest·템플릿 버전·snapshot ID/hash가 불변이다. 새 공식 PDF가 생성·공개되기 전에는 기존 `GENERATED`를 `SUPERSEDED`로 바꾸지 않는다. 기존 파일은 동일 저장 파일만 재다운로드한다. |
| `IssuanceHold` | 아직 revision으로 정형화되지 않은 중요·반대 정보에 대한 지속적인 발급 보류 | Case 1:N. 서버 저장 `ACTIVE/RELEASED` 상태와 생성·해제 사유/주체/시각, 관련 `evidenceRevision`, 감사 ID를 가진다. `ACTIVE`이면 승인 결정과 신규 발급 예약·공개를 차단한다. Case 상태나 Evidence Revision을 대신하지 않는다. |
| `ApprovalAuditEvent` | 요청·검토·결정·무효화·발급 보류·발급 게이트의 보안 감사 | Request/Decision/Case에 연결되는 append-only 사건. CaseActivity와 목적이 다르며 서로 ID로 연결한다. 영수증의 서버 식별자는 연결하되 원문 토큰은 기록하지 않는다. |

`Case.APPROVED_FOR_REPORT`는 그 시점의 승인 적격성을 나타내는 제안 상태이며 `ApprovalRequest.APPROVED`와 같은 enum이 아니다. 증거가 바뀌어도 과거 결정은 `APPROVED`로 남고, 현재 근거에 대한 승인 적격성만 `STALE`로 계산한다. `CURRENT`는 근거·내용 바인딩의 필요조건일 뿐 발급 허가가 아니다. `ACTIVE` 발급 보류, Case 종료 또는 렌더 경합도 별도로 검사한다. `SUPERSEDED`는 이전 PDF가 새 공식 PDF로 대체된 ReportVersion 상태이고 `STALE`과 동의어가 아니다. `AnalysisJob.COMPLETED`와도 분리한다.

## 식별자와 수명

| ID | 발급/범위 | 수명·연결 규칙 |
|---|---|---|
| `caseId` | Spring 발급 불투명 UUID, `(tenantId, caseId)` 소유 | 사건 전체 수명 동안 유지. 출처가 추가·교체되어도 재발급 금지. |
| `analysisRunId` | Spring 분석 실행/attempt 식별자, tenant 범위 | 재분석마다 새 ID. 현행 `analysisRequestId`는 호출 상관 ID이며 동일 개념이라고 가정하지 않는다. 도입 시 대응표 필요. |
| `clusterId` | 해당 분석 실행 안의 식별자 | 재계산 시 구성원과 값이 달라질 수 있다. 단독 전역 키 금지. |
| `fanReportId`, `evidenceId` | Spring 제보 접수·파일 증거의 별도 식별자, tenant/event 범위 | `fanReportId`는 제보 자체, `evidenceId`는 소속 증거 항목이다. 파일 ID만으로 제보를 추정하지 않는다. 현행 `TicketAudit.id`/`reporterId`와 구분하며 독립 제보 영속 모델·대응표가 선행되어야 한다. |
| `eventId` | 고객사 공연 식별자 | Case·모든 출처의 공연이 같아야 한다. 현행 매크로에는 문자열 `eventId`, VLM 저장 흐름에는 공연 ID가 없다. |
| `tenantId` | 서버의 인증된 활성 멤버십에서 결정 | 모든 Case·출처·스냅샷·감사 행의 소유 범위. body/query/header 값 단독 신뢰 금지. |
| `approvalRequestId`, `approvalDecisionId`, `evidenceSnapshotId`, `reportVersionId`, `holdId`, `reviewReceiptId` | Spring 불투명 UUID | 각각 요청·단일 결정·불변 스냅샷·보고서 버전·발급 보류·서버 영수증 기록의 영속 ID. 클라이언트에 전달하는 `reviewReceiptToken`은 별도 비밀값이다. 재요청/재캡처/새 보고서 revision은 새 ID. |

모든 시각은 UTC ISO 8601, API 필드는 camelCase로 제안한다. ID 형식·길이와 마이그레이션은 목표 MySQL 스키마와 함께 확정해야 한다. 기존 `Long auditId`를 새 UUID로 묵시 변환하거나 클라이언트가 Case ID를 선택하게 하지 않는다.

## ApprovalRequest·ApprovalDecision 필드와 변경 규칙

| 객체 | 필수 기록 | 변경 규칙 |
|---|---|---|
| Request | `approvalRequestId`, `tenantId`, `caseId`, `reportVersionId`, `reportRevision`, `reportContentSha256`, `reportTemplateVersion`, 요청자 `requestedBy`, `requestedAt`, `evidenceSnapshotId`, `evidenceRevision`, `manifestSha256`, 사유, `status`, 서버가 범위화한 `idempotencyKey`, `caseVersionAtSubmit`, `version` | 본문·대상·요청자·시각은 불변. `PENDING`만 `WITHDRAWN`/`INVALIDATED` 가능하며 사유·시각·감사 ID를 별도 기록. `APPROVED`/`REJECTED`는 terminal. 보완 후 새 ReportVersion revision과 요청 생성. 키 원문은 로그·일반 조회에 노출하지 않는다. |
| Decision | `approvalDecisionId`, `approvalRequestId`, `decidedBy`, `decision`(`APPROVE`/`REJECT`), 사유, `decidedAt`, `reviewedEvidenceSnapshotId`, `reviewedManifestSha256`, `reviewedReportContentSha256`, 서버 `reviewReceiptId`, `approvalAuditEventId` | 결정은 append-only, 요청별 1건. 영수증 소비·결정·감사를 한 트랜잭션으로 기록한다. 오판 정정은 기존 행 수정 대신 사유가 있는 후속 이벤트와 새 ReportVersion revision·요청·결정으로 처리. 거절도 기술 장애와 구분한다. |

ANALYST와 REVIEWER는 Specs `03`에서 서로 다른 역할이다. ANALYST는 자료·증거를 준비하고, 권한 있는 REVIEWER가 보고서 초안과 근거를 검토해 요청을 제출한다. ANALYST 단독 제출은 허용하지 않는다. 요청자와 승인자는 서로 다른 **인증된 사용자 ID**여야 하며 같은 사람이 여러 역할을 가졌어도 자기 승인은 불가하다. 승인자는 증거 manifest, 필요한 원본, 봉인된 보고서 내용에 접근할 권한이 있어야 한다. `GET /api/v1/approval-requests/{approvalRequestId}/review-package`와 승인 요청·증거 상세 GET은 검토 대상을 조회할 뿐 영수증을 발급하지 않는다. 인증된 APPROVER가 명시적으로 같은 경로의 POST를 요청하면 서버가 검토 대상·권한·범위를 검증한 뒤 범위가 고정된 `ReviewReceipt`를 저장하고 별도 `reviewReceiptToken`을 발급한다. 결정 시 토큰 digest·범위·기한·상태를 다시 확인하고 한 번만 소비한다. 동일 키/본문의 결정 재시도는 먼저 저장된 멱등 결과를 반환하며 새 결정을 만들거나 토큰을 재소비하지 않는다. 열람 기록만으로 사람이 내용을 이해했다고 단정하지 않으므로 명시적 검토 확인도 받는다. 결정·영수증 소비·ReportVersion 상태·Case 상태·감사 이벤트 작성은 한 트랜잭션의 성공/실패 단위다.

`ApprovalAuditEvent`에는 `approvalAuditEventId`, `tenantId`, `caseId`, 관련 `approvalRequestId`/`approvalDecisionId`/`reviewReceiptId`/`holdId`, 행위자 또는 SYSTEM, 행위 종류, UTC 시각, 전후 상태·근거 revision, `evidenceSnapshotId`/`manifestSha256`, 사유, 상관 `requestId`를 기록한다. 원문 영수증 토큰과 증거·개인정보 원문은 기록하지 않는다. 사건 정정이나 승인 무효화도 새 이벤트로 추가하며 기존 이벤트의 UPDATE/DELETE는 허용하지 않는다.

## 선행 조건과 API 관계

신규 API는 Spring 인증 문맥, tenant membership, 공연별 객체 접근, 비공개 파일 권한, 버전·감사 저장소가 구현·검증되기 전에는 **활성화 금지**다. 공개 API가 요청한 `tenantId`를 권한 근거로 삼지 않는다. 서버 장애는 증거 없음이나 승인 반려로 저장하지 않는다.

## MVP 수명·버전 규칙

`DISMISSED`는 근거를 기록한 조사상 기각이며 `CLOSED`는 관리상 종료다. 두 상태에서 신규 중요 증거가 발견되면 원 Case를 직접 재개하지 않고 별도 `OPEN` 후속 Case를 생성한다. 원 Case는 `relatedCaseId`의 대상이 될 뿐 종료·결정·PDF·감사 이력을 바꾸지 않는다. 후속 Case에는 새 출처와 연결 사유가 필수이고 기존 승인을 자동 승계하지 않는다. Specs `04`의 명시적 `REOPEN` 제안과 다른 MVP 선택이며 Specs DEC-12 동기화가 필요하다.

`APPROVED_FOR_REPORT → CLOSED`는 두 경로만 허용한다. `closureDisposition=GENERATED_REPORT`는 현재 승인된 ReportVersion의 PDF가 공식 저장소에 `GENERATED`로 공개되고 다른 미해결 발급 보류·진행 중 예약이 없을 때만 가능하다. `closureDisposition=ISSUANCE_ABANDONED`는 REVIEWER가 별도 포기 사유를 기록하고 미발급·미예약 사실을 검증한 뒤 `REPORT_ISSUANCE_ABANDONED` 및 `CASE_CLOSED` 감사를 같은 트랜잭션에 남긴다. 후자는 PDF 발급 완료가 아니다. `DISMISSED → CLOSED`는 `closureDisposition=NOT_APPLICABLE`이다. `CLOSED`에서는 신규 렌더와 `GENERATION_FAILED` 재시도가 불가하나 이미 생성된 PDF는 인증·인가·접근 감사 후 동일 파일로 재다운로드할 수 있다. 진행 중 렌더 예약이 있으면 종료는 409로 거절하고, 종료가 먼저 확정되면 렌더 예약·공개가 실패한다.

새 DRAFT·승인만으로 기존 `GENERATED`를 `SUPERSEDED`로 바꾸지 않는다. 새로운 ReportVersion의 PDF 후보를 비공개 저장한 뒤 짧은 최종화 DB 트랜잭션에서 승인·보류·Case 상태를 재확인하고 새 `GENERATED` 참조와 이전 `SUPERSEDED`를 함께 확정한다. 다운로드는 이 확정 참조만 제공하므로 DB 실패 후보는 공식 공개되지 않는다. 중요 증거 변경으로 과거 승인이 현재 근거에 맞지 않는 것은 `approvalEligibility=STALE`이며 이전 PDF 상태와 결정 이력을 임의 수정하지 않는다. `ACTIVE` 발급 보류는 별도 서버 통제 상태이며 Evidence Revision 증가 전에도 신규 승인 결정·발급 예약·공개를 막는다.

`FAN_REPORT` Case의 생성 입력에는 영속 `fanReportIds`가 필요하고, `evidenceIds`는 해당 제보에 속한 파일 항목으로만 연결한다. 서버가 각 제보와 파일의 존재·tenant/event·소유 관계를 확인한다. 독립 팬 제보 영속 모델이 없는 현재 Backend에서는 이 생성 경로를 구현할 수 없으며 프런트 합성 ID를 실제 제보 ID로 간주하지 않는다.

ReportVersion `revision`은 같은 Case에서 증가하는 **내용 버전**이고, ReportVersion `version`은 상태 갱신 충돌 검사용이다. Case `caseVersion`과 ApprovalRequest `version`도 각각 다른 optimistic lock이다. ReportVersion을 생성할 때 서버가 내용·템플릿·snapshot ID/hash를 봉인하고 `Case.currentReportVersionId`를 원자적으로 바꾸며 `caseVersion`을 올린다. PENDING 요청이 있었다면 같은 트랜잭션에서 `INVALIDATED` 처리하고, 승인된 뒤 새 내용 revision을 만들면 Case를 `IN_REVIEW`로 되돌리고 과거 승인 적격성을 `STALE`로 표시한다. `DRAFT`를 포함해 봉인된 내용의 제자리 수정은 허용하지 않으며 보완은 새 revision으로 만든다. ReportVersion당 Request는 최대 하나이고 반려·철회·무효화 후에도 같은 revision으로 재제출하지 않는다. 과거 `APPROVED` Decision은 그대로 남는다.

Case API는 내부 `caseVersion`을 응답의 `caseVersion`으로 표시하고 **모든 Case 명령**의 기대값을 `expectedCaseVersion`으로 통일한다. Specs 관리자 API 초안의 `version`/`expectedVersion`은 같은 Case optimistic lock을 뜻하지만 여러 자원을 함께 갱신하는 승인 명령에서는 의미가 불명확하다. Specs는 아직 `PROPOSED`이므로 동기화 작업에서 필드명을 함께 바꿔야 한다. ApprovalRequest는 `version`/`expectedRequestVersion`, ReportVersion은 `version`/`expectedReportVersion`으로 별도 검사한다. `evidenceRevision`과 ReportVersion `revision`은 optimistic lock이 아닌 내용·근거 버전이다.

## 겹치는 경로와 단일 승인 명령

| 경로 | MVP 권장 계약 | Specs 제안과의 차이 |
|---|---|---|
| `GET /api/v1/cases` | `eventId`·`status`·`page`·`pageSize` 필터, tenant/event 서버 범위, `items,page,pageSize,hasNext` | Specs 관리자 YAML에는 `hasNext`와 필터가 없다. Case 필수 필드도 아래 초안에 맞춰 동기화해야 한다. |
| `GET /api/v1/cases/{caseId}` | `caseId,eventId,type,status,caseVersion,evidenceRevision,allowedActions,createdAt` 필수 | Specs의 `version`을 `caseVersion`으로 변경하고 확장 필드를 추가해야 한다. |
| `POST /api/v1/cases/{caseId}/transitions` | `toStatus,expectedCaseVersion,reason`; 종료 시 `closureDisposition`과 조건별 보고서 ID/포기 사유. 미요청 READY는 검토·근거 필요 상태로 복귀 가능; APPROVED_FOR_REPORT 직접 지정 불가 | Specs의 `expectedVersion`과 직접 APPROVED_FOR_REPORT 선택지, 종료 조건을 변경해야 한다. |
| `POST /api/v1/approval-requests/{approvalRequestId}/decision` | APPROVER의 **유일한 승인/반려 쓰기 명령**. Request·ReportVersion·Case·감사를 원자 처리 | Specs `POST /report-versions/{id}/approve`는 별도 결정 경로로 함께 활성화하지 않는다. 구현 전 Specs 경로 폐기 또는 동일 명령으로의 통제된 전환 결정이 필요하다. |

Specs `07`에는 ReportVersion approve 요청의 `decision`이 있으나 Specs 관리자 OpenAPI에는 `expectedVersion,reason`만 있다. 두 Specs 초안의 차이도 동기화 대상이다. 이번 Backend OpenAPI는 새 경로만 정의하며 현재 구현된 `/api/v1/audits/**`에는 아무 영향이 없다.
