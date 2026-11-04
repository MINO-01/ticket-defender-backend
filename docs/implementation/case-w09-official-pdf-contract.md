# W09 공식 PDF 발급 계약과 운영 제한

`case-w09-official-pdf-v1.yaml`은 구현된 W09 API 계약이다. W08b의
`case-approval-input-v1.json`은 사람 승인용 입력 아티팩트이며 공식 PDF 템플릿이 아니다.
기존 `/api/v1/audits/{auditId}/report`는 계속 HTTP 409으로 차단된다.

## 모델과 상태

- `ReportVersion`은 W08b의 `APPROVED` 상태와 불변 content/revision/digest를 유지한다. PDF 작업 상태를 추가하지 않는다.
- `OfficialPdfIssuance`는 한 `ApprovalDecision`에 대한 가변 작업 레코드다. `RESERVED`, `RENDERING`, `OBJECT_STORED`, `FAILED`, `STALE`, `GENERATED` 상태와 재시도 기록을 관리한다.
- `OfficialPdfArtifact`는 객체 버전, PDF SHA-256, 크기, 발급 시각, 승인, 보고서, 증거, 템플릿 고정을 갖는 불변 완료본이다. DB UNIQUE는 Decision당 작업 1건과 Issuance당/Decision당 Artifact 1건을 보장한다.
- 수정 보고서는 새 `ReportVersion` revision과 그 revision에 대한 새 인간 승인을 필요로 하므로, 과거 Artifact는 보존되고 새 Decision에만 새 Artifact가 연결된다.

## 현재 적격성

사람의 불변 `ApprovalDecision`과 현재 발급 적격성은 구분된다. 예약 시와 최종화 직전에 Case 잠금 뒤 아래를 재확인한다.

- 승인 결과, Request/Receipt 소비, 독립성, ReportVersion/Case 상태, 모든 고정 ID, revision, digest
- 현재 manifest와 W06 무결성, W07 Object Lock, 보존 정책, 현재 evidence revision
- 활성 `IssuanceHold`, 승인 입력 템플릿 및 공식 PDF 템플릿 digest

결과는 `CURRENT`, `STALE`, `BLOCKED`이며, 과거 인간 승인을 수정하지 않는다. `READ_COMMITTED`와 Case-first 잠금은 이 경계에 참여하는 내부 writer를 직렬화할 뿐이며, 이를 따르지 않는 외부/직접 DB 정책 writer나 검증 뒤의 새 정책 변경까지 자동으로 막지는 않는다.

`InternalIssuanceHoldWriteService`는 공개 API가 아닌 내부 Hold 경계다. Hold 등록/해제는 Case FOR UPDATE 뒤에 수행하고, 같은 트랜잭션에서 기존 `case_activities`에 `SYSTEM` 역할의 `ISSUANCE_HOLD_PLACED`/`ISSUANCE_HOLD_RELEASED` 감사(사유에 Hold ID와 reasonCode)를 기록한다. 감사 기록이 실패하면 Hold 변경도 롤백된다. 추가 migration은 없다. 운영 Hold 해제 권한은 미확정이므로 웹 API는 제공하지 않는다.

## 템플릿, 저장, 재시도

공식 템플릿은 `official-pdf-templates/official-case-pdf-v1.xhtml`이며, 버전과 SHA-256을 Issuance/Artifact에 고정한다. 템플릿은 서버 소유 XHTML만 사용하고 외부 URL, 동적 HTML, 이미지, stylesheet를 허용하지 않는다. 승인 시점에 이 PDF 레이아웃 자체를 별도 사람 검토했다는 사실은 현재 보장하지 않는다.

작업은 짧은 DB 예약 → 트랜잭션 밖 렌더링, private S3 업로드 → 짧은 DB 최종화 순서다. 결정 UUID 기반의 server-owned 키와 S3 객체 metadata의 issuance ID를 사용하고, version/size/SHA-256을 검증한 뒤에만 Artifact를 만든다. 객체 업로드 뒤 DB 최종화가 실패하면 같은 Issuance의 `OBJECT_STORED` 후보를 검증, 회수하므로 다른 공식 PDF를 만들지 않는다.

렌더링은 Issuance에 고정된 템플릿 버전과 SHA-256이 현재 보존된 아티팩트와 일치할 때만 수행하며, 불일치는 `OFFICIAL_TEMPLATE_CHANGED`(STALE)다. 같은 봉인 입력은 같은 PDF 바이트를 만든다. PDF 문서 ID는 issuance ID에서, CreationDate는 불변 `requestedAt`에서 고정하므로 결정적 객체 키의 412 재사용 경로가 바이트 동일성으로 검증된다. 치환 값은 XML 안전 escape만 사용한다.

## 재시도 lease와 fencing

`ticket-defender.official-pdf.attempt-lease`(합성 격리 초기값 `PT5M`, 운영 SLA 아님) 안의 RUNNING 시도가 있으면 retry는 `409 OFFICIAL_PDF_ISSUANCE_IN_PROGRESS`이며 렌더링을 시작하지 않는다. lease가 지나면 retry가 이전 시도를 `RECOVERY_REPLACED`로 닫고 새 시도를 연다. 객체 기록, 최종화, 종료 표시는 Issuance 잠금 아래 요청한 attempt ID가 최신 RUNNING 시도인지 확인한다. 밀려난 작업자는 `OFFICIAL_PDF_ATTEMPT_SUPERSEDED`로 거부되고 새 시도의 상태를 덮어쓰지 않는다. S3 호출 타임아웃(호출당 최대 2분)과 증거 재검증을 합치면 정상 작업도 5분을 넘을 수 있다. 이 경우 가용성만 영향을 받고 fencing과 `ifNoneMatch` 저장으로 중복 공식 PDF는 생기지 않는다.

object key, presigned URL, 자격증명, 원시 receipt token은 API 및 감사 응답에 포함하지 않는다.

## Specs 12 대조

PDF는 봉인된 기록에 있는 값만 표시한다. Case/ReportVersion ID, UTC와 한국 시각, 승인 결정, 요청자와 승인자의 멤버십 가명 ID, 보고서/증거 revision과 digest, 출처 수, 검증 상태, 템플릿 버전, 봉인된 제목/평가/한계/결론, 자동 확정 아님 고지를 포함한다. 회사명, 문서 분류, Track 1 그래프 요약, Track 2 대조 결과표, 변경 이력 섹션은 현재 봉인 보고서 입력(`case-approval-input-v1`)에 없으므로 생성하지 않는다. 이를 넣으려면 별도 승인된 보고서 입력 계약이 필요하다. 새 공식 PDF 공개 시 이전 `GENERATED`를 `SUPERSEDED`로 바꾸는 규칙(DEC-09)과 실패 후보 객체 정리는 정책 미결정이므로 구현하지 않는다.

## 운영 정책 제한

기본값은 `ticket-defender.official-pdf.enabled=false`다. flag만 켜도 동작하지 않고,
현재 코드는 명시적 `SYNTHETIC_ISOLATED` 모드에서만 실행된다. `TENANT_ADMIN`은 합성 격리 개발을 위한 잠정 역할일 뿐 최종 운영 권한 정책이 아니다. 개인정보 처리 근거, 위탁/국외 이전, 보존, 파기, 운영 발급, 다운로드 권한이 승인될 때까지 실운영 발급은 안전하게 차단한다.

## 합성 격리 게이트 (Issue #66)

PR #48의 게이트는 `enabled=true`와 `operational-mode=SYNTHETIC_ISOLATED` 두 설정만 확인했다. 운영 서버에 이 두 줄과 loopback PDF 버킷만 지정하면 운영 DB의 실제 고객사 Case와 팬 원본으로 공식 PDF가 발급될 수 있었다. 이제 `OfficialPdfOperationGate`는 아래 조건을 모두 요구하고 하나라도 확인할 수 없으면 차단한다.

| 단계 | 조건 | 실패 코드 (409) |
| --- | --- | --- |
| 환경 | `enabled=true` | `OFFICIAL_PDF_DISABLED` |
| 환경 | `operational-mode=SYNTHETIC_ISOLATED` | `OFFICIAL_PDF_OPERATION_NOT_APPROVED` |
| 환경 | `synthetic-isolation-id`가 UUID, 데이터소스가 단일 호스트 loopback MySQL 또는 `jdbc:h2:mem:`, 공식 PDF와 제보 원본과 W07 보존 저장소 endpoint가 비어 있거나 사용자 정보 없는 loopback | `OFFICIAL_PDF_ISOLATION_UNVERIFIED` |
| 범위 | Case 고객사가 `official_pdf_synthetic_tenants`에 같은 `isolation_id`로 등록되어 있고, Case 생성 시각과 모든 출처(연결 시각, 제보 접수 시각, 증거 생성 시각, Track 1 분석 완료 시각)가 `registered_at` 이후 | `OFFICIAL_PDF_SYNTHETIC_SCOPE_UNVERIFIED` |

- 등록부(`docs/database/official-pdf-synthetic-isolation-migration.sql`)는 마이그레이션이 비워 둔 채 만든다. 공개 API가 없고 격리 환경 준비 절차(테스트 fixture 또는 격리 DB의 SQL)만 등록한다. 따라서 운영 DB는 설정을 바꿔도 등록 행이 없어 발급되지 않는다.
- `registered_at`은 UTC다. 애플리케이션의 Case, 제보, 증거, 출처 시각도 `LocalDateTime.now(ZoneOffset.UTC)` 기준이다. SQL 등록은 열을 생략해 기본값 `UTC_TIMESTAMP(6)`를 쓰며(`INSERT INTO official_pdf_synthetic_tenants (tenant_id, isolation_id) VALUES (...)`), 세션 시간대를 따르는 `NOW()`나 `CURRENT_TIMESTAMP`는 쓰지 않는다. 음수 오프셋 세션의 `NOW()`는 등록 시각을 과거로 기록해 등록 이전 데이터를 허용할 수 있다. 테스트 fixture는 UTC 값을 직접 넣는다.
- 상태를 확정하는 쓰기 트랜잭션(`reserveForDecision`, `finalizeIssuance`, 다운로드 감사 `auditDownload`)은 Case `FOR UPDATE` 다음에 등록 행을 `FOR SHARE`로 읽는다. 최종화가 먼저 잠금을 잡으면 직접 SQL의 등록 해제(DELETE)는 산출물이 확정될 때까지 대기하고, 확정 전 해제가 진행 중이면 최종화의 잠금 조회가 대기한 뒤 해제 결과를 보고 실패한다. 해제가 확정된 뒤의 신규 발급과 다운로드는 차단된다. 해제 DELETE는 등록 행만 잠그므로 Case 우선 잠금 순서와 교착이 생기지 않는다. 조회와 렌더링 준비(`renderSource`, 읽기 전용 트랜잭션)는 잠그지 않고 최종화가 다시 확인한다.
- `registered_at` 조건은 기존 실제 고객사를 나중에 합성으로 등록해 과거 데이터로 발급하는 것을 막는다.
- 호출 위치: eligibility(BLOCKED와 코드 반환), request, retry, detail, history, download는 권한 확인 뒤에 검사해 범위 밖 사용자에게 등록 여부를 드러내지 않는다. 쓰기 경로는 `reserveForDecision`과 `finalizeIssuance`가 Case 잠금 아래에서, `renderSource`가 렌더링과 객체 저장 전에 다시 검사한다.
- 예약 전 차단은 발급 기록, 시도, 감사 행을 만들지 않는다. 예약 뒤 등록이나 격리 조건이 사라지면 기존 `markTerminal`이 `FAILED`와 실패 코드를 시도와 감사 기록에 남기고 아티팩트와 `GENERATED`는 만들지 않는다.
- 합성 범위를 확인할 수 없는 기존 아티팩트는 DB 이력을 그대로 보존하고 detail, history, download만 409로 막는다. 적격성이 STALE이 된 기존 문서의 다운로드 정책(DEC-14)은 이번에 정하지 않았다.

검증에 사용한 합성 데이터는 테스트 fixture가 만든 고객사, 제보, 증거와 4바이트 또는 합성 PNG 원본이며, 각 fixture가 Case 생성 전에 등록 행을 만든다. 실제 개인정보나 외부 데이터는 사용하지 않았다.

자동으로 확인할 수 없는 신뢰 경계는 다음과 같다. 이 게이트는 데이터가 실제 개인정보가 아님을 증명하지 않는다.

- DB 쓰기 권한이 있는 관리자는 등록 행을 넣을 수 있다.
- loopback 주소는 운영 DB나 저장소로 향하는 로컬 프록시나 터널로 위장할 수 있다.
- 이미지 바이트와 제보 내용이 합성인지는 서버가 판정할 수 없다.

따라서 실운영 발급은 개인정보 처리 근거, 위탁과 국외 이전, 보존과 파기, 발급과 다운로드 권한 정책이 승인될 때까지 계속 차단한다. #46의 남은 항목(한국어 레이아웃 검증), DEC-09(SUPERSEDED, 실패 후보 파기), DEC-14(발급 역할, Hold 운영, 다운로드 재검증)는 이 Issue에서 다루지 않는다.
