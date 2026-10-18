# 증거 스냅샷·버전·승인 적격성 정책

> **MVP 정책 반영 / PROPOSED / 미구현.** Specs `12_증거관리_PDF.md`의 불변 원본·revision·보고서 버전 원칙을 Case 승인 경계에 적용한다. 현재 Backend의 `evidenceImageUrls`와 매크로 감사 행만으로는 이 정책을 충족하지 못한다. 기존 공식 PDF 발급 차단은 유지한다.

## 무엇을 고정하는가

`EvidenceSnapshot`은 `evidenceSnapshotId`, 서버 결정 `tenantId`, `caseId`, `caseVersionAtCapture`, `evidenceRevision`, `capturedAt`, `manifestSchemaVersion`, `manifestSha256`, 선택된 근거 항목 목록을 가진 불변 manifest다. 항목별 ID·버전·digest와 원본 object version, 선택된 추출·교정 revision, 대조 결과/범위, Track 1 실행/알고리즘 버전, 출처 연결 버전, 조사 판단 버전을 묶는다. 팬 제보 출처 항목에는 제보 ID `fanReportId`를, 해당 제보의 파일 증거 항목에는 증거 ID `evidenceId`와 소속 `fanReportId`를 함께 고정한다. 분석 finding 등 다른 출처에는 존재하지 않는 `fanReportId`를 요구하지 않는다. 보고서 본문·템플릿은 **별도 ReportVersion**의 `revision`, `contentSha256`, `templateVersion`으로 봉인하고 snapshot ID/hash를 참조한다. Case의 기존 ID와 과거 snapshot·ReportVersion은 유지한다.

| 항목 유형 | manifest에 포함할 최소 출처·버전 |
|---|---|
| 원본 이미지 | 영속 증거 항목 `evidenceId`, 소속 제보 `fanReportId`, 저장 자산 `assetId`, 비공개 저장소 object key 및 object version, bytes SHA-256, byteSize, MIME, 접수/제공 시각, 보관 조건. URL·민감 이미지 내용은 목록 API에 넣지 않는다. |
| 추출·수정 결과 | `extractionId`, revision, 모델명/버전, 실행 ID·시각, 필드별 `VISIBLE/ABSENT/UNREADABLE/AMBIGUOUS`, 관찰 위치, 교정자·시각·사유. 원본 결과도 보존한다. |
| 예매 대조 | `matchId`, revision, 허용된 공연·조회 범위, 대조 시각, `MATCH_CANDIDATE/NO_MATCH/UNVERIFIABLE`, 근거·한계. 조회 불가/장애를 `NO_MATCH`로 바꾸지 않는다. |
| 그래프 finding | `analysisRunId`, 실행 입력/graph snapshot 식별자, `clusterId`, finding revision, 알고리즘/버전, 구성원·신호 요약, risk score 및 설명. 점수는 확률·확정 판정이 아니다. |
| 검토 판단·출처 | 출처 연결 버전, 승인 근거에 채택된 판단·미확인 사실·한계와 각각의 revision. 근거와 무관한 운영 메모는 제외. |
| 별도 ReportVersion | snapshot ID·manifest digest를 참조하고 보고서 본문/입력 `contentSha256`, `templateVersion`, 내용 `revision`을 보관. 이 값은 Evidence Revision에 섞지 않는다. |

manifest는 정해진 스키마와 안정적인 순서의 canonical JSON을 만들고 **`manifestSha256` 필드 자체를 제외한 bytes**에 SHA-256을 계산한다. 각 항목은 `itemId`, `itemVersion`, `itemSha256`(파일이면 실제 bytes digest, 구조화 결과면 정규화 결과 digest), 출처 타입/ID를 가진다. `FAN_REPORT` 출처에서는 `sourceRecordId=fanReportId`, 제보 소속 `EVIDENCE_ASSET`에서는 `sourceRecordId=evidenceId`와 `fanReportId`를 함께 기록한다. 서버는 저장된 제보·증거 관계와 Case의 tenant/event 범위, 출처 연결 버전을 확인하며, 존재하지 않거나 다른 제보·tenant·공연에 속한 ID는 연결하지 않는다. 클라이언트가 제출한 ID만으로 소속을 확정하지 않는다. 현재 Backend에는 독립 팬 제보 영속 모델이 없어 이 검증은 선행 구현 의존성이다. ReportVersion `contentSha256`도 봉인된 보고서 입력의 canonical JSON bytes로 계산하며 digest 필드는 제외한다. `templateVersion`이 가리키는 템플릿 artifact는 같은 버전에서 바꾸지 않는다. 생성 후 읽기 전용 저장과 DB 참조 무결성 검증이 필요하다. SHA-256은 **무결성 확인** 수단이다. 개인정보 익명화, 진정성·작성자 확인, 법적 증거력, 유출 위험 제거를 자동 보장하지 않는다. 개인정보 처리·보유·파기·접근 통제는 별도 법률·운영 검토가 필요하다.

## 버전 연결 방식 비교와 선택

| 방식 | 장점 | 한계 | 결정 |
|---|---|---|---|
| 스냅샷 ID만 비교 | 단순 | 같은 ID가 가리키는 항목을 잘못 덮어쓰면 탐지 불가 | 단독 사용 불가 |
| 항목별 버전·digest만 비교 | 변경 항목 식별 용이 | 목록 누락·추가, 정렬·의미 변경을 놓칠 수 있음 | manifest와 함께 사용 |
| Case `caseVersion`만 비교 | 동시 상태·배정 변경 감지 | 배정 같은 비증거 변경도 충돌, 내용 동일성 증명 불가 | 명령의 optimistic lock에 사용 |
| **선택: snapshot ID + manifest SHA-256 + 항목 버전/digest + `evidenceRevision`** | 승인된 입력 전체와 변경 원인을 함께 검증 | 원자 갱신·정규화 규격 필요 | 승인/발급 게이트. `caseVersion`은 경합 감지에 추가 |

`evidenceRevision`은 승인 근거의 실질적 내용 또는 판단에 영향을 줄 수 있는 새 중요/반대 증거가 바뀔 때 증가한다. 관련성 평가 전의 중요 정보는 먼저 서버 저장 `IssuanceHold.ACTIVE`로 발급·새 결정이 중단될 수 있으며, 이때 아직 revision이나 새 snapshot을 만든 것으로 간주하지 않는다. `caseVersion`은 상태·배정·증거 연결 등 모든 Case 변경에 증가한다. 승인 요청의 `caseVersionAtSubmit`은 제출 경합 검사용이며 과거 버전 숫자만 일치한다고 승인 적격성이 생기지는 않는다. 승인자 결정 시 현재 Case 상태, 활성 보류 없음, snapshot ID/hash, revision과 항목 해시 **및 ReportVersion ID/revision/contentSha256/templateVersion**을 재확인한다. 공식 발급 예약·공개 시에도 동일 바인딩과 현재 발급 자격·보류를 재확인한다.

`READY_FOR_APPROVAL` 전이와 스냅샷 고정은 하나의 트랜잭션으로 처리하고, `caseVersionAtCapture`에는 전이 **후**의 버전을 기록한다. 근거 revision이 그대로인 재검토라면 이미 고정된 동일 snapshot을 재사용할 수 있다. REVIEWER는 현재 snapshot을 참조하는 불변 DRAFT ReportVersion을 만들며 이때 `Case.currentReportVersionId`/`caseVersion`이 원자적으로 갱신된다. 요청 제출의 `expectedCaseVersion`은 **ReportVersion 생성 후 현재** Case 버전을 가리킨다. 제출 때 ReportVersion `version`/`contentSha256`도 검사한다. 봉인 후 본문·템플릿을 제자리 수정하지 않고 새 ReportVersion revision을 만든다.

## Evidence Revision 변경 분류

| 변경 | 처리 |
|---|---|
| 원본 교체·추가·제외, 승인 근거로 선택한 OCR/VLM 추출 결과·분석관 교정값 변경 | `evidenceRevision` 증가, 새 snapshot. 원본/선택 결과의 이전 버전 보존. |
| 예매 대조 결과 또는 조회 범위, 채택한 Track 1 실행·finding·알고리즘 버전 변경 | `evidenceRevision` 증가. 단순히 미채택 실행이 완료된 것만으로는 증가하지 않음. |
| Case 출처 연결 변경, 승인 근거에 들어간 조사 판단 변경 | `evidenceRevision` 증가·새 snapshot. PENDING 요청 무효화 또는 기존 승인 적격성 `STALE`. |
| 기존 결론에 영향을 줄 수 있는 새 중요·반대 정보 발견 | 우선 `IssuanceHold.ACTIVE`를 서버에 기록해 승인 결정·발급 예약·공개를 차단한다. 관련성이 확인되면 `evidenceRevision` 증가·새 snapshot·재승인; 무관성이 확인되면 근거·감사 후 보류 해제. 평가 전에는 새 snapshot이 있다고 주장하지 않는다. |
| 단순 담당자 배정, 무관한 운영 메모, UI 표시 문구, 사건 판단에 영향을 주지 않는 미채택 부가 정보 | `caseVersion`/CaseActivity 등 해당 이력만 갱신. 증거 revision은 증가하지 않음. 다만 REVIEWER 변경은 PENDING 요청 권한을 재검토. |
| 보고서 본문·요약·필수 표시 문구 또는 템플릿 변경 | Evidence Revision과 별개로 새 ReportVersion `revision`과 `contentSha256`/`templateVersion`; 이전 승인 재사용 금지. |

분류가 애매한 신규 정보는 판단 영향 평가가 끝날 때까지 지속되는 ACTIVE 보류로 신규 승인 결정과 발급을 막는다. 모든 Case 필드 변경을 무조건 승인 무효화 조건으로 삼지 않는다. snapshot의 원본 object key와 민감한 추출 원문은 비공개 저장소에 두고 승인 감사에는 참조 ID·버전·digest·행위 메타데이터만 기록한다.

## 지속적인 IssuanceHold

`IssuanceHold`는 서버 인증 문맥의 `tenantId`, `eventId`, `caseId`, `holdId`, `reasonCode`, `status=ACTIVE/RELEASED`, 등록 주체(사용자 ID 또는 SYSTEM 이벤트)·시각, `evidenceRevisionAtOpen`, 생성 감사 ID를 보존한다. 해제 시 권한 있는 REVIEWER가 관련성 판단 근거, 해결 주체·시각·사유, `evidenceRevisionAtResolution`, 해제 감사 ID를 추가한다. 값은 이력으로 보존하며 원행·과거 승인을 삭제하지 않는다. 15분 같은 자동 만료로 보류를 해제하지 않으며 모든 ACTIVE 보류가 해소되어야 한다.

중요 정보가 무관하다고 확인되면 원본/접수 참조와 판단 한계를 기록하고 보류만 해제할 수 있다. 관련성이 확인되면 먼저 승인 근거를 새 revision·snapshot으로 고정하고 PENDING 요청을 `INVALIDATED` 또는 기존 승인 적격성을 `STALE`로 처리한 뒤 보류를 해제한다. 평가 전 또는 저장·감사 실패 시 보류를 해제하지 않는다. 보류 등록 시 기존 미소비 검토 영수증도 폐기하고, 무관성 확인 후에는 새 패키지·영수증으로 다시 검토한다. `CURRENT`인 과거 승인이라도 ACTIVE 보류가 하나라도 있으면 사용할 수 없다. 보류는 UI 플래그가 아니라 발급 예약·최종 공개와 같은 DB 직렬화 경계에서 검사되는 지속 상태다. 공개 전 비공개 임시 PDF는 다운로드할 수 없고 차단되면 폐기/격리하며 `REPORT_PUBLICATION_BLOCKED`를 감사한다.

## 승인자가 실제 조회한 대상 확인

`GET /api/v1/approval-requests/{approvalRequestId}/review-package`는 권한 있는 APPROVER에게 현재 PENDING 요청의 봉인된 검토 대상만 반환한다. 인증된 tenant/event·Case 범위와 snapshot·ReportVersion 바인딩을 검사하고 민감 응답은 캐시하지 않는다. GET은 `ReviewReceipt`를 생성·소비·폐기하거나 승인 상태를 변경하지 않는다. 단순 조회와 상세 페이지 열기는 검토 완료 확인이 아니다.

`POST /api/v1/approval-requests/{approvalRequestId}/review-package`는 영수증을 서버에 생성하는 명시적 명령이다. 권한 있는 APPROVER에게 PENDING 요청과 봉인된 ReportVersion의 ID/revision/contentSha256/templateVersion, snapshot ID/manifestSha256 및 항목별 버전 메타데이터를 같은 서버 시점에 반환한다. 단순 상세 페이지 열람은 영수증 발급이나 검토 완료가 아니다. ACTIVE 보류가 있으면 새 영수증을 발급하지 않는다. 같은 승인자·요청의 새 발급은 이전 미소비 영수증을 원자적으로 폐기하며 자동 POST 재시도는 새 토큰을 만들 수 있으므로 클라이언트가 결과를 확인해야 한다. 서버는 암호학적으로 안전한 난수로 만든 고엔트로피 `reviewReceiptToken`의 digest만 보관하고 별도 내부 `reviewReceiptId`에 인증된 승인자·tenant·event·Case·요청·snapshot ID/hash·ReportVersion ID/digest, `issuedAt`, `expiresAt`, 상태를 묶는다. 초기 권장 유효기간은 **15분**이며 최종 값은 운영 설정으로 확정한다. 응답은 `Cache-Control: no-store`이고 원문 토큰은 응답에서 한 번만 전달하며 로그·감사에 복제하지 않는다.

`POST .../decision`은 토큰, 명시적 `reviewConfirmed=true`, 클라이언트가 확인한 snapshot/hash·report digest를 받고 서버 저장 원본과 재비교한다. 토큰이 위조·만료·소비·폐기되었거나 사용자/범위/버전이 다르거나 ACTIVE 보류가 생겼으면 409이고 Decision은 없다. 근거 변경, ReportVersion 변경, 보류 등록, 요청 철회·무효화 시 이전 토큰은 사용할 수 없다. 결정을 생성할 때 내부 영수증 상태를 1회 `CONSUMED`로 바꾸고 Decision과 감사에 내부 `reviewReceiptId`를 연결하는 작업을 한 트랜잭션으로 처리한다. 동일 actor/tenant/operation의 동일 멱등 키·본문 재시도는 영수증 재검사보다 먼저 기존 결과를 반환하고 새 결정을 만들지 않는다. 다른 키·본문으로 소비된 토큰을 다시 사용하면 409다. POST로 발급된 영수증과 명시적 확인은 사람이 내용을 이해했다는 증명이 아니며, 프런트 상세 페이지 열기만으로 검토 완료를 기록하지 않는다. 원본 파일은 별도 tenant/event·역할 검사 후 열람한다.

## V1 승인 후 V2 증거의 결과

1. ANALYST가 근거를 준비하고 REVIEWER가 Case의 `evidenceRevision=1`을 `EvidenceSnapshot V1`로 고정한다. 동일 snapshot을 참조하는 ReportVersion revision 1의 내용 digest·템플릿을 봉인한 뒤 요청한다.
2. APPROVER가 권한 있는 원본/추출/대조/분석 근거와 봉인된 보고서 내용을 확인하고 review receipt를 받아 snapshot ID/hash·report digest를 결정에 기록한다.
3. 승인이 원자적으로 기록되면 Request와 ReportVersion은 `APPROVED`, Case는 `APPROVED_FOR_REPORT`가 된다. 이것은 해당 snapshot·ReportVersion에 한정된 적격성이다.
4. 중요한 반대 정보가 먼저 도착했지만 아직 판단되지 않았다면 `IssuanceHold.ACTIVE`와 감사를 저장하고 신규 승인·발급을 중단한다. 이 시점에는 V2 snapshot이나 `STALE`을 성급하게 기록하지 않는다. 관련성이 확인되어 V2 근거가 추가·교정되면 `evidenceRevision=2`, 새 snapshot, Case `IN_REVIEW`, 현재 승인 적격성 `STALE`, `APPROVAL_BASIS_STALE`을 원자 기록한 뒤 보류를 해제한다. V1 결정/기존 PDF는 수정하지 않는다.
5. 공식 보고서 요청이 V2를 참조하거나 V1 ReportVersion의 본문 digest가 다르면 발급 게이트는 **409 `EVIDENCE_VERSION_MISMATCH` 또는 `REPORT_CONTENT_MISMATCH`**를 반환하고 새 공식 PDF/발급 상태를 만들지 않는다. 새 검토·V2 snapshot·새 ReportVersion revision·독립 승인이 필요하다. 저장소 장애는 5xx이며 승인 반려로 표현하지 않는다.
6. V2를 재검토한 뒤 새 보고서가 필요하면 새 snapshot·ReportVersion·요청·결정·PDF를 거쳐 `APPROVED_FOR_REPORT → CLOSED (GENERATED_REPORT)`로 종료한다. 새 보고서를 발급하지 않기로 하면 권한 있는 REVIEWER가 먼저 별도 `POST /api/v1/cases/{caseId}/evidence-review-completions`로 현재 V2 revision·활성 snapshot ID/manifest digest와 `reviewOutcome=NO_NEW_REPORT`·사유에 묶인 `EVIDENCE_REVIEW_COMPLETED` CaseActivity를 저장한다. GET·메모·종료 요청으로 이 활동을 자동 생성하지 않는다. 그 활동이 종료 명령 전에 확정된 뒤에만 `reviewActivityId`, `reviewedEvidenceRevision`, 종료·신규 미발급 사유와 함께 `IN_REVIEW → CLOSED (NO_NEW_REPORT_AFTER_STALE)`를 요청한다. 서버는 활동의 tenant/event/Case·당시 REVIEWER 권한·현재 revision/snapshot/hash, V1의 불변 Decision이 **증거 revision 증가로** `STALE`인지, ACTIVE 보류·PENDING 요청·렌더 예약 부재와 과거 PDF 이력을 같은 직렬화 경계에서 확인하고 종료·CaseActivity·ApprovalAuditEvent를 원자 기록한다. 활동 저장 또는 종료 감사 저장 실패 시 각각의 명령 전체를 롤백한다. V1 PDF가 없을 수도 있으며 이때 과거 공식 ReportVersion ID 목록은 빈 배열이다.

`GET /api/v1/cases/{caseId}/sources`는 현재 불변 snapshot이 있으면 ID·manifest digest를 함께 제공하고, 검토 완료 명령은 서버 저장 현재 snapshot과 대조한다. V3 증거 반영이나 현재 snapshot/hash 변경 뒤에는 V2 검토 활동을 재사용하지 않는다. 클라이언트가 현재 `reviewedEvidenceRevision`만 알고 있어도 사전 저장 활동이 없으면 종료를 거부한다. ReportVersion 본문 변경만으로 승인이 `STALE`이 된 사건은 증거 revision 증가에 따른 이 관리상 종료 경로의 대상이 아니다.

여기서 V1/V2는 **EvidenceSnapshot 버전**이다. ReportVersion `revision` 및 `/api/v1`은 다른 축이다. V2가 반영된 뒤 V1 승인으로 새 PDF를 발급하지 않는다. 이미 `GENERATED`로 저장된 V1 PDF는 인증·인가·접근 감사 후 **동일 저장 파일**을 재다운로드할 수 있으며 과거 버전임을 표시한다. `NO_NEW_REPORT_AFTER_STALE` 종료는 과거 V1 승인·PDF의 존재와 발급 이력을 그대로 두지만 V2의 승인·발급 완료로 표시하지 않는다. `SUPERSEDED`는 새 ReportVersion의 공식 PDF 공개가 성공한 뒤에만 이전 생성본에 붙으며 `STALE`·종료와 다르다. `GENERATION_FAILED`는 동일 봉인 입력·snapshot·템플릿으로만 제한적으로 재시도하고 현재 적격성·보류·Case 상태를 확인한다. `CLOSED`에서는 그 재시도도 금지한다. 새 증거를 담은 보고서는 새 snapshot·ReportVersion revision·요청·결정을 거친다. 생성된 PDF의 in-place 재렌더/덮어쓰기는 금지한다. 보유·삭제·법적 보전 기간은 별도 정책 대상이다.

## 동시성·멱등성 선택

| 경합 | 선택한 제약과 트랜잭션 | 경쟁 결과 |
|---|---|---|
| 동일 승인 요청 재제출 | `(tenantId, actorId, operation, idempotencyKey)` 유일성 + 정규화 body hash | 같은 키·같은 내용은 동일 Request/응답. 같은 키·다른 내용은 409 `IDEMPOTENCY_KEY_REUSED`. |
| 다른 키의 중복 활성 요청 | `(tenantId, reportVersionId, revision)`별 활성 PENDING 최대 1건; Case의 현재 발급 대상 활성 요청도 1건을 MySQL active-slot/조건부 갱신으로 강제 | 409 `APPROVAL_REQUEST_ALREADY_PENDING`. 반려·철회 후 같은 revision 재제출은 409 `REPORT_REVISION_CLOSED`. |
| 두 승인자의 동시 결정·철회 경합 | Request `version`·`PENDING` 조건부 갱신, `UNIQUE(approval_request_id)` Decision, Case/ReportVersion 기대 버전 검사와 감사 insert를 한 트랜잭션 | 1명만 성공. 다른 결정은 409 `APPROVAL_ALREADY_DECIDED`, 이미 철회된 요청은 409 `APPROVAL_REQUEST_NOT_PENDING`; 같은 멱등성 키 재시도는 기존 응답. |
| 상태 변경 경쟁 | Case optimistic lock(`caseVersion`) + `WHERE status=:expected AND version=:expected` 조건부 갱신 | 실패한 명령은 409 `CASE_VERSION_CONFLICT`, 상태·감사 변경 없음. |
| 증거·보고서 내용 변경과 결정 경쟁 | Case row를 같은 순서로 잠그거나 단일 조건부 갱신 경로 사용; `evidenceRevision`·manifest·`currentReportVersionId`·ReportVersion digest 재검증 | V2/새 내용 revision 선행이면 승인 실패. 승인 선행이면 새 근거/내용 반영과 동시에 현재 적격성 상실. |
| 승인 완료와 보고서 발급·중복 렌더 경쟁 | 짧은 DB 트랜잭션에서 Case/Request/Decision/ReportVersion과 봉인 입력을 검증하고 **단일 발급 예약**을 조건부 갱신·고유성 제약으로 획득 | 이미 예약 중/GENERATED면 409 또는 동일 멱등성 응답. 외부 렌더러는 트랜잭션 밖에서 봉인 입력만 사용. |
| 렌더 도중 중요 증거 발견 | 중요 정보의 발급 보류/새 revision과 완료 시 재검사를 동일 직렬화 경계로 처리. PDF는 비공개 임시 위치에 저장 후 현재 자격을 다시 확인 | V2/보류가 먼저면 결과를 공식 저장물로 공개하지 않고 실패 원인 기록. 이미 `GENERATED`로 원자 발급된 과거 파일은 보존·과거 표시. |
| 보류 등록·해제와 발급 예약/결정 경합 | Case/ACTIVE 보류 슬롯을 동일 잠금 순서로 검사·변경. 해제는 관련성 판단·revision/승인 처리 후 감사와 함께 확정 | 보류가 먼저면 409 `ISSUANCE_HOLD_ACTIVE`; 예약/결정이 먼저면 그 결과의 역사성은 보존하고 이후 새 발급은 보류. 단순 해제만으로 STALE 승인은 재사용 불가. |
| 종료와 렌더 예약/공개 경합 | `CLOSED` 전이는 `GENERATED_REPORT`, `ISSUANCE_ABANDONED`, `NOT_APPLICABLE` 기각, 또는 V2 사전 검토 활동 뒤 `NO_NEW_REPORT_AFTER_STALE`의 각각 다른 가드에 더해 예약 없음·ACTIVE 보류 없음·PENDING 요청 없음·`expectedCaseVersion`을 원자 검사. 새 분기는 종료 전에 확정된 `reviewActivityId`의 tenant/event/Case·REVIEWER·`NO_NEW_REPORT`·현재 revision/snapshot/hash, 과거 증거 변경에 따른 STALE 승인과 서버 저장 PDF 참조까지 검증하고 종료·감사를 함께 확정 | 인증·tenant/event/Case 접근 범위 확인 뒤 `expectedCaseVersion` 불일치는 409 `CASE_VERSION_CONFLICT`, 다음 `reviewedEvidenceRevision` 불일치는 409 `EVIDENCE_VERSION_MISMATCH`; 이후 활동 ID 미존재·타 범위는 동일 404, 같은 사건의 잘못된 활동 유형은 409 `EVIDENCE_REVIEW_NOT_COMPLETED`, 활동 revision·snapshot ID·manifest digest와 현재 근거 불일치는 409 `EVIDENCE_REVIEW_MISMATCH`. 활동 ID의 존재·소속은 앞선 버전 오류에 영향을 주지 않는다. 예약 중 종료는 409 `REPORT_RENDER_IN_PROGRESS`, 보류 중은 409 `ISSUANCE_HOLD_ACTIVE`. 종료가 먼저면 신규 예약·실패 재시도·공개는 409; 기존 GENERATED 파일 조회만 가능. 감사 저장 실패는 5xx와 전체 롤백. |

PDF bytes의 오브젝트 저장과 DB 트랜잭션 자체를 하나의 분산 트랜잭션으로 간주하지 않는다. 렌더 결과를 먼저 비공개·불변 후보 object에 저장해 checksum을 확인하고, **짧은 최종화 DB 트랜잭션**에서 Case/hold/approval/revision/ReportVersion/예약을 재검증한 뒤 후보 object 참조와 `GENERATED`, 이전 생성본 `SUPERSEDED`, PDF hash·감사를 함께 확정한다. 다운로드 API는 이 DB 확정 참조만 제공하므로 트랜잭션 실패 후보는 공개되지 않으며 별도 정리 대상이다. 최종화와 보류 등록·Case 종료는 같은 잠금 순서로 직렬화한다. 신규 PDF의 공개가 먼저 확정되면 후속 보류·신규 증거는 과거 파일을 수정하지 않고 신규 발급만 차단한다.

Optimistic locking은 사용자 검토 시간이 긴 업무에 적합하고 충돌을 명확히 알린다. DB 유일성 제약은 애플리케이션의 선행 조회만으로 막지 못하는 동시 중복을 막는다. 조건부 갱신은 상태/버전 검사를 원자화한다. 전 구간 장시간 비관적 잠금만 사용하는 대안은 락 대기와 장애 복구 비용이 커서 선택하지 않는다. 메모리 락/프런트 버튼 비활성화만으로는 여러 서버 인스턴스·직접 API 호출을 막지 못한다. MySQL의 nullable UNIQUE 동작과 실제 DDL은 스키마 구현 Issue에서 검증한다.

Decision과 감사 이벤트는 UPDATE/DELETE 금지 권한, append-only 쓰기 경로, 참조 제약, 접근 감사, 보존/법적 보전 정책으로 보호한다. 정정은 원행 삭제가 아닌 보상 이벤트다. 백업·관리자 권한·파기 절차까지 포함한 운영 통제가 필요하다.
