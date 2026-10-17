# Case·Approval MVP 정책, Specs 동기화와 후속 구현 작업 (Issue #20)

> **사용자 채택 MVP 정책을 반영한 Backend PROPOSED / 문서 전용 / 미구현.** Issue #20의 본문은 이 환경에서 GitHub 페이지를 가져오지 못해 직접 확인하지 못했다. 범위는 사용자 제공 작업 지시와 읽은 Backend/Specs 파일에 근거한다. Specs `contracts/registry.yaml`의 관리자 API는 아직 `PROPOSED`이고 `governance/decision-log.md`의 DEC-09·DEC-12는 `PENDING`이다. 사용자 선택은 이번 Backend 초안의 기준이며 공유 Specs 승인 완료를 뜻하지 않는다. Specs 파일은 읽기 전용으로 검토했고 수정하지 않았다.

## 이번 Backend 초안에 반영한 MVP 선택

| ID | 반영한 정책 | Specs 관계·남은 동기화 |
|---|---|---|
| D1 | `DISMISSED`/`CLOSED` 직접 재개 없음. 중요한 새 근거는 `relatedCaseId`·`relationReason`을 가진 독립 후속 Case. 원 사건의 종료·승인·PDF·감사는 불변이고 승인은 승계하지 않음. | Specs `04`의 명시적 `REOPEN` 제안 및 DEC-12와 차이. 공유 결정·상태도·생성 API 동기화 필요. |
| D2 | ANALYST는 자료 준비, REVIEWER는 준비 확인·ApprovalRequest 제출, APPROVER는 독립 결정. ANALYST 단독 제출 불가; 자기 승인 금지. | Specs `03`에서 역할이 서로 다르고 `12`도 REVIEWER 제출. 요청 철회/담당 변경 가드를 Backend 초안에 추가. |
| D3 | 불변 ReportVersion revision + EvidenceSnapshot을 승인 대상으로 고정. Request → ReportVersion/Snapshot, Decision → Request. 동일 revision의 활성 Request 최대 1개, 반려·철회 후 새 revision. `POST /approval-requests/{id}/decision`만 권위 있는 결정 경로. | Specs `07`/관리자 YAML의 `/report-versions/{id}/approve`를 독립 결정으로 동시에 활성화하지 않음. DEC-09 및 경로 전환 계획 필요. |
| D4 | `GENERATED` PDF는 동일 저장 파일 재다운로드, `GENERATION_FAILED`만 동일 봉인 입력으로 제한 재시도. V2 근거 신규 발급은 새 snapshot·ReportVersion·결정 필요. | Specs `12`의 불변 PDF·실패 재시도와 정합. 다운로드/렌더/신규 발급의 API 의미를 분리. |
| D5 | 근거 의미·선택 입력 변경 또는 중요한 반대 증거 발견은 Evidence Revision 증가/적격성 보류. 배정·무관한 메모·UI 표현은 제외. 본문·템플릿은 별도 ReportVersion revision/digest. | Specs의 불변 snapshot 원칙과 정합. 각 변경 분류·manifest 스키마는 공유 계약 동기화 필요. |

## 최종 리뷰 결함에 대한 Backend MVP 보완

| 항목 | 반영한 제안 | Specs 관계 |
|---|---|---|
| 종료와 발급 | `APPROVED_FOR_REPORT → CLOSED`는 현재 PDF가 공식 `GENERATED`이거나 REVIEWER가 별도 사유·감사로 `ISSUANCE_ABANDONED`를 기록한 경우만 허용. ACTIVE 보류·렌더 예약 중 종료 거절; CLOSED 신규 발급·재시도 금지, 기존 PDF 재다운로드 허용. | Specs `04`의 단순 종료 화살표와 `07`의 렌더 경로에 가드·포기 의미 추가 필요. 발급 포기는 발급 완료가 아님. |
| 대체 시점 | 새 ReportVersion의 공식 PDF 공개가 성공한 최종화 때에만 이전 `GENERATED → SUPERSEDED`. DRAFT·승인·근거 `STALE`만으로 변경 금지. | Specs `04`는 신규 버전 **승인 시** SUPERSEDED라서 변경 필요. DEC-09는 여전히 PENDING. |
| 지속 보류 | 서버 저장 `IssuanceHold.ACTIVE/RELEASED`; 중요 신규 정보의 관련성 확인 전에도 결정·발급 예약·공개 차단. 관련성이 확인되면 revision/snapshot/승인 재처리 후 해제, 무관성이 확인되면 근거·감사 후 해제. | Specs `12`의 증거 변경/발급 게이트에 보류 상태와 감사·경합 추가 필요. |
| 검토 영수증 | 내부 `reviewReceiptId`와 일회용 `reviewReceiptToken` 분리. 서버 기록을 만드는 `POST /approval-requests/{id}/review-package`에서 발급하고 응답 캐시 금지. 인증 승인자·tenant/event/Case·Request·snapshot/hash·ReportVersion/digest·발급/만료 시각에 묶음. 초기 TTL 15분 권장, 최종 운영 설정 미정. 소비·Decision·감사 원자 처리, 동일 멱등 재시도는 저장 결과 반환. 원문 토큰 감사 금지. | Specs 관리자 API에 ApprovalRequest/Decision/ReviewReceipt 경계 추가 필요. 사람의 이해 증명은 아님. |
| READY 복귀 | 요청이 없으면 REVIEWER가 `IN_REVIEW`/`NEEDS_EVIDENCE`로 사유·`expectedCaseVersion`과 함께 직접 복귀. PENDING이면 먼저 철회·반려·무효화 명령이 Request/ReportVersion/Case를 원자 처리. | Specs `04`의 복귀 가드 및 관리자 API 전이 계약 구체화 필요. |
| 기본 역할 | ANALYST 근거 준비, REVIEWER 초안·요청·상태, APPROVER 독립 결정. TENANT_ADMIN 일반 상태/초안 수정·APPROVER 초안 작성은 기본 권한으로 두지 않음. 예외 관리자 개입은 별도 감사 절차. CaseActivity와 ApprovalAuditEvent 읽기 범위 분리. | Specs `03`의 TENANT_ADMIN 상태 변경, TENANT_ADMIN·APPROVER 초안 생성, 감사 조회 범위와 다름. 공유 권한 결정 필요. |
| 제보 출처 | `fanReportIds`는 제보, `evidenceIds`는 그 제보 소속 파일. CaseSourceReference의 `FAN_REPORT`와 `EVIDENCE_ASSET`를 별도 연결하고 서버가 소유·tenant/event 검사. 파일 ID만으로 제보 추정 금지. | Specs `02`의 `reportIds`와 관리자 YAML의 `findingIds/evidenceIds` 명칭·관계를 동기화. 독립 팬 제보 영속 모델 선행. |

`APPROVED`는 기존 Decision의 역사적 사실이고, `approvalEligibility=CURRENT/STALE`은 현재 증거·본문의 바인딩이다. `CURRENT`만으로 발급되지 않으며 ACTIVE 보류·Case 상태·렌더 예약도 확인한다. `SUPERSEDED`는 실제 새 공식 PDF가 이전 생성본을 대체했다는 ReportVersion 상태이고 `STALE`과 다르다. 이전 승인·PDF를 제자리 수정하지 않는다. 현재 `AuditReportService`의 공식 발급 차단은 계속 유지한다.

## 겹치는 API의 권장 단일 계약

| 메서드·경로 | Backend 권장 계약 | Specs 관리자 API 초안과 차이·처리 |
|---|---|---|
| `POST /api/v1/cases` | `eventId,type,reason` 필수. `GRAPH_CLUSTER`는 `findingIds`, `FAN_REPORT`는 영속 `fanReportIds`, `COMBINED`는 둘 다 필수. `evidenceIds`는 명시한 제보의 소속 파일. 후속이면 `relatedCaseId,relationReason` 쌍 필수; 응답 새 OPEN Case. | Specs 관리자 YAML의 `findingIds/evidenceIds`와 `02`의 `reportIds`를 제보 ID/파일 ID로 분리해야 함. 독립 팬 제보 영속 모델이 없으면 이 API 구현 불가. |
| `GET /api/v1/cases` | 서버 tenant/event 범위, `eventId,status,page,pageSize`, 응답 `items,page,pageSize,hasNext`. | Specs YAML은 `page,pageSize`와 `items,page,pageSize`만 필수. 필터·hasNext 및 Case 스키마 동기화. |
| `GET /api/v1/cases/{caseId}` | `caseId,eventId,type,status,caseVersion,evidenceRevision,allowedActions,createdAt` 필수, 생성 후 `currentReportVersionId` 제공. | Specs YAML의 `version`을 `caseVersion`으로, `type`은 유지. tenantId는 서버 저장 범위이며 요청/응답 권한 근거가 아님. |
| `POST /api/v1/cases/{caseId}/transitions` | `toStatus,expectedCaseVersion,reason` 필수, REVIEWER만. 미요청 READY 복귀 허용; CLOSED는 `closureDisposition` 필수, 생성 PDF ID 또는 별도 발급 포기 사유가 조건부 필수. `APPROVED_FOR_REPORT` 직접 설정·`REOPEN` 금지. | Specs의 `expectedVersion`, 직접 승인 상태 enum, 종료 가드/응답을 변경해야 함. APPROVER 승인은 Decision 명령으로만 반영. |
| `POST /api/v1/cases/{caseId}/report-versions` | 현재 snapshot으로 봉인된 DRAFT revision 생성, `expectedCaseVersion` 검사. `currentReportVersionId`와 Case 버전 원자 갱신, 기존 승인 적격성 STALE. | Specs `07`의 경로를 유지하면서 ReportVersion 바인딩/응답 상세화. |
| `POST /api/v1/cases/{caseId}/approval-requests` | REVIEWER가 `reportVersionId,evidenceSnapshotId,expectedCaseVersion,expectedReportVersion,expectedReportContentSha256,reason` 제출. | Specs에 Request 자원·멱등성·활성 슬롯 규칙 추가 필요. |
| `GET /api/v1/approval-requests/{approvalRequestId}/review-package` | APPROVER에게 봉인된 검토 대상만 조회 제공. GET은 영수증 생성·소비·폐기 및 승인 상태 변경을 하지 않음. | Specs에 읽기 전용 검토 패키지 경로와 접근 범위 추가 필요. |
| `POST /api/v1/approval-requests/{approvalRequestId}/review-package` | APPROVER에게 봉인된 패키지를 제공하고 서버 일회용 영수증을 생성. 동일 사용자·요청의 기존 미소비 영수증 폐기, `Cache-Control: no-store`. | Specs에는 검토 영수증 경로가 없으며, 단순 GET 조회와 서버 기록 생성의 의미를 분리해야 함. |
| `POST /api/v1/approval-requests/{approvalRequestId}/decision` | 유일한 APPROVER 결정 경로. `expectedCaseVersion,expectedRequestVersion,expectedReportVersion`은 각 자원의 lock 버전. ACTIVE 보류 없음, 일회용 영수증 토큰·snapshot/hash·report digest 비교. 서버 영수증 ID를 Decision/감사에 연결. | Specs `/report-versions/{id}/approve`는 폐기하거나 새 경로로 전환해야 하며 **두 독립 결정 명령 금지**. Specs `07`의 `decision`과 관리자 YAML의 누락도 정리. |
| `POST /api/v1/approval-requests/{approvalRequestId}/withdraw` | REVIEWER가 PENDING만 철회, 사유·시각·감사 기록, 동일 키/본문 멱등 재시도. | Specs에 없는 신규 경로 및 ReportVersion `WITHDRAWN` 상태. |
| `POST /api/v1/report-versions/{reportVersionId}/render` / `GET .../download` | 전자는 APPROVED_FOR_REPORT·ACTIVE 보류 없음·미종료 조건의 신규 공식 발급 예약/실패 재시도, 후자는 CLOSED에서도 기존 불변 PDF bytes 조회. 공개 성공 때만 이전 PDF SUPERSEDED. | Specs `07` 경로 의미와 종료·보류·대체 시점을 상세화. 실제 발급 권한과 차단 해제는 별도 승인·구현 필요. |

**버전 명칭:** Case 응답의 `caseVersion` ↔ 모든 Case 명령의 `expectedCaseVersion`을 선택한다. Specs `version`/`expectedVersion`은 같은 Case optimistic lock이나 다중 자원 명령에서 모호하므로 공유 초안 변경이 필요하다. ApprovalRequest `version` ↔ `expectedRequestVersion`, ReportVersion의 상태 lock `version` ↔ `expectedReportVersion`을 따로 검사한다. ReportVersion 내용 `revision`과 Case `evidenceRevision`은 lock 버전과 별개다. 경로·HTTP 메서드는 위 표, `operationId`와 세부 필드·HTTP 오류는 [OpenAPI 초안](../api/case-approval-v1-proposal.yaml)에 둔다. 모든 자원 ID는 서버가 인증 tenant/event 범위와 대조하고 타 고객사·숨겨야 할 공연 범위는 404, 같은 범위의 역할 부족은 403이다.

| HTTP | 대표 애플리케이션 코드 | 적용 경계 |
|---|---|---|
| 400 / 401 / 403 / 404 | `INVALID_REQUEST` / `UNAUTHENTICATED` / `FORBIDDEN`·`SELF_APPROVAL_FORBIDDEN` / `RESOURCE_NOT_FOUND` | 형식·인증·같은 tenant 역할 부족·타 tenant/숨겨야 할 공연 자원. 클라이언트 tenantId는 권한 근거가 아님. |
| 409 | `CASE_VERSION_CONFLICT`, `REPORT_VERSION_CONFLICT`, `EVIDENCE_VERSION_MISMATCH`, `REPORT_CONTENT_MISMATCH`, `REVIEW_RECEIPT_MISMATCH`, `REVIEW_RECEIPT_UNUSABLE`, `ISSUANCE_HOLD_ACTIVE` | Case·ReportVersion lock, 증거/본문/영수증 불일치, 소비·만료·폐기 영수증 또는 ACTIVE 보류. Decision·PDF 발급 없음. |
| 409 | `APPROVAL_REQUEST_ALREADY_PENDING`, `APPROVAL_ALREADY_DECIDED`, `APPROVAL_REQUEST_NOT_PENDING`, `REPORT_REVISION_CLOSED`, `REPORT_RENDER_IN_PROGRESS`, `REPORT_NOT_GENERATED`, `REPORT_ALREADY_GENERATED`, `IDEMPOTENCY_KEY_REUSED` | 중복·경합·미발급 종료 시도·종료된 revision/요청·렌더 상태·같은 키 다른 본문. 같은 키/본문은 저장된 응답. |
| 422 / 500 / 503 | `VALIDATION_FAILED` / `INTERNAL_ERROR` / `DEPENDENCY_UNAVAILABLE` | 업무 입력 제약 / 서버 장애 / 필수 의존성 장애. 장애를 승인 거절·증거 없음으로 저장하지 않음. |

OpenAPI의 전역 bearer 보안·operation별 `x-required-roles`는 **미구현 요구사항**이다. `render`는 발급 역할 미결정으로 `x-activation-status: BLOCKED_POLICY_AND_SECURITY`이며 역할 목록이 비어 있다. 빈 역할 목록이 임의 사용자 허용을 뜻하지 않는다. IssuanceHold의 등록·해제와 예외 관리자 개입은 이 초안에 공개 쓰기 API를 추가하지 않은 서버 업무 명령 제안이며 별도 권한·감사 설계가 필요하다. 어떤 경로도 인증·tenant membership·event scope·파일 접근 통제 구현 전 다중 고객용으로 활성화하지 않는다.

## Specs 읽기 전용 동기화 작업

1. `governance/decision-log.md` DEC-12에 종료 사건의 후속 Case 정책을 정식 기록하고 `04_사용자시나리오_업무흐름_상태전이.md`의 `REOPEN` 제안과 맞춘다. Specs가 별도 확정 결정을 내리면 이 Backend 초안을 재검토한다.
2. `contracts/admin-api-v1-proposal.yaml`의 Case 스키마·필터·페이지·전이 enum·`expectedVersion`을 단일 Case 계약으로 맞추고 `07_API명세_외부내부_계약.md`의 표도 수정한다. 특히 직접 `APPROVED_FOR_REPORT` 전이를 제거하고 READY 복귀, `closureDisposition`, 미발급 포기, CLOSED 이후 렌더 금지를 반영한다. `02_기능명세서.md`의 `reportIds`, 관리자 YAML의 `evidenceIds`를 영속 `fanReportIds`와 소속 파일 `evidenceIds`로 분리한다.
3. `03_역할권한정의.md`와 Backend MVP의 TENANT_ADMIN 상태/초안 권한, APPROVER 초안 권한, CaseActivity·ApprovalAuditEvent 조회 범위 차이를 공유 결정으로 정리한다. 예외 관리자 개입은 일반 업무 API와 분리된 승인·감사 절차로 설계한다. Specs는 이번 작업에서 수정하지 않는다.
4. `04_사용자시나리오_업무흐름_상태전이.md`의 `GENERATED → SUPERSEDED(신규 버전 승인 시)`를 신규 PDF 공식 공개 성공 시점으로 동기화하고, `12_증거관리_PDF.md`에 ACTIVE 보류 등록·해제·발급 경합을 추가한다.
5. `07_API명세_외부내부_계약.md`, `12_증거관리_PDF.md`, `contracts/admin-api-v1-proposal.yaml`, DEC-09에 ReportVersion + EvidenceSnapshot + ApprovalRequest/Decision의 한 승인 흐름, 검토 패키지 GET 조회와 별도 POST 영수증 발급, 일회용 서버 영수증, 요청 철회·무효화 상태, 반려·철회 뒤 새 revision을 반영한다. 기존 `/report-versions/{id}/approve`는 새 결정 경로와 독립적으로 구현하지 않고 계약 전환 시점에 폐기/명시적 호환 오류를 정한다. 구현 전 경로이므로 기존 서비스 API 호환성을 깨는 코드 변경은 없다.
6. Specs 관리자 YAML의 approve 요청은 `expectedVersion,reason`만 요구하지만 Specs `07`은 `decision,reason,expectedVersion`을 적었다. 단일 명령으로 수렴할 때 두 초안의 필드와 version 대상을 함께 수정한다.
7. `contracts/registry.yaml`의 관리자 API artifact hash·버전·상태는 Specs 계약이 실제로 수정·승인되는 별도 작업에서만 갱신한다. 이번 Backend 작업은 Specs를 수정하지 않는다.

## 여전히 정책·구현 선행 조건인 항목

| ID | 결정·작업 | 현재 경계 |
|---|---|---|
| D6 | Case 중복 후보·수동 병합 규칙 | 군집 ID는 재계산마다 달라진다. 자동 병합 금지. |
| D7 | 인증 방식, tenant/event membership, 증거·PDF 접근과 감사 보유·삭제·legal hold | 현재 Backend에 구현되지 않아 신규 다중 고객 API 활성화 금지. 개인정보 법률·운영 검토 별도. |
| D8 | 현행 `TicketAudit Long id`/`analysisRequestId`/VLM URL과 새 ID·자산 매핑, 독립 `fanReportId` 영속 모델 | 실제 데이터 이행표·MySQL 스키마 구현은 별도 Issue. 파일 ID를 팬 제보 ID로 대용하지 않음. |
| D9 | 공식 발급 권한과 `AuditReportService` 차단 해제 조건 | 새 render 경로는 미구현·활성화 차단. 기존 `/api/v1/audits/{auditId}/report`는 계속 409 `AUDIT_REPORT_ISSUANCE_DISABLED`. |
| D10 | 보유·파기 기간과 법적 보전 | PDF 미생성 종료의 감사 가능한 `ISSUANCE_ABANDONED` 절차와 새 PDF 공개 성공 시 `SUPERSEDED`는 이번 Backend MVP 초안에 반영. 공유 Specs DEC-09 및 법률·운영 정책 승인 전이므로 최종 정책으로 표시하지 않음. |
| D11 | 발급 보류 등록·해제 명령의 공개 API 여부, 실제 역할·SLA 및 운영 절차 | 이번 초안은 서버 저장 모델·게이트만 제안하고 공개 쓰기 경로는 추가하지 않음. 구현 전 권한·감사·경합 테스트 필요. |
| D12 | ReviewReceipt TTL 운영 설정과 발급 토큰 관리 | 15분은 초기 권장값이며 최종 운영 설정·저장소 만료/폐기·감사 보존은 별도 검토. |

## 선행 작업과 Issue 분할 제안

1. **인증·tenant/공연 격리 (TD-09, TD-SEC-01)**: Spring 인증 문맥, membership, 서비스/Repository/오브젝트 접근 범위, 타 tenant ID 404, 역할 403, 자기 승인 차단 테스트. 완료 전 새 다중 고객 API 비활성.
2. **MySQL 기반 및 식별자 이행 (TD-07)**: 현행 데이터 diff, additive migration 계획, legacy 감사↔신규 출처 매핑, optimistic version과 tenant FK·고유성 검증. 기존 migration/history 유지.
3. **제보·증거 자산/추출·대조 (TD-10/11/12)**: 독립 영속 `fanReportId`와 소속 `evidenceId`, 실제 접수·비공개 파일 원본, SHA-256/버전, 교정 revision, 분석/대조 실패 구분. 개인정보·모델 전송 법률/운영 검토 별도.
4. **Case 읽기·출처·활동 계약 구현 (TD-13a 제안)**: Case ID 수명, tenant/event 검증, 목록·상세·출처·이력, 원 Case와 후속 Case 연결·승인 비승계, 중복 후보 테스트. 분석 완료가 Case 확인으로 승격되지 않음.
5. **Case 명령·동시성 (TD-13b 제안)**: REVIEWER 상태 전이/배정, `caseVersion`/`evidenceRevision`, 종료 Case 직접 재개 거절, 이벤트 append-only, 다중 요청·타 tenant·경합 테스트.
6. **스냅샷·ReportVersion·승인 Request/Decision (TD-14a 제안, TD-AUD-01 연계)**: immutable manifest와 내용 digest, REVIEWER 제출, 단일 APPROVER 결정·서버 저장 일회용 검토 영수증과 결정 감사 연결, 철회·무효화, revision별 활성 슬롯·멱등성, V1→V2 경합·감사 테스트.
7. **발급 보류·종료·보고서 게이트 (TD-14b 제안)**: 지속 `IssuanceHold`와 관련성 검토, 승인된 정확한 snapshot/ReportVersion 내용만 봉인 입력으로 렌더, 단일 발급 예약, PDF 불변·hash, V2/ACTIVE 보류 차단·공개 직전 재검사·공개 성공 시 SUPERSEDED, GENERATED 종료 또는 감사 가능한 발급 포기, 기존 파일 다운로드 감사. 기존 `AuditReportService` 차단 해제와 발급 권한은 이 단계의 별도 리뷰 대상이며 이번 작업에서 하지 않는다.
8. **클라이언트 계약 검증 (TD-16/17 연계)**: OpenAPI 승인 후 Backend/Frontend 타입·오류 fixture, 권한별 UI와 다중 고객 E2E. 프런트 합성 Case를 서버 계약으로 역수입하지 않는다.

## 기술 구현과 별도 검토

개인정보 처리 목적·법적 근거, 고객사의 원천 데이터 처리 권한, 위탁/재위탁·국외 전송, 보유·파기·정보주체 권리, 원본 이미지의 마스킹과 법적 보전은 기술 테스트 통과만으로 완료되지 않는다. 공식 보고서는 조사 지원 문서이며 자동 위법 판단·티켓 취소·과징금 산정 기능으로 연결하지 않는다.
