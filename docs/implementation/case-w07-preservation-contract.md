# W07 증거 보존 정책 게이트와 불변 객체 검증 계약

## 상태와 범위

이 문서는 Issue #40의 기술 검증 기반을 설명한다. W07은 운영 보존 정책의 법률 승인이나 고객사 승인을 완료하지 않는다. DEC-09, DEC-12 및 승인 관련 공유 문서의 PENDING 또는 PROPOSED 항목은 운영 승인으로 간주하지 않는다.

운영 보존 명령은 기본적으로 비활성이다. W07은 외부 관리자 API를 공개하지 않으며, 운영 정책을 승인하거나 실제 W02 개인정보 객체를 복제하는 경로도 제공하지 않는다. 내부 서비스는 격리된 합성 객체와 서버에서 미리 등록한 정책을 검증하는 기반으로만 사용한다. 기존 `approvalEligibility=BLOCKED`, W05의 `approvalEligible=false`, `renderable=false`, 공식 PDF HTTP 409 차단은 바꾸지 않는다.

## 기존 구현 감사

1. W02 객체 버킷은 버전 관리와 Object Lock이 비활성이라는 전제로 동작한다. 업로드 실패 복구와 만료 정리는 객체 key 삭제를 사용하므로 W07이 이 버킷의 설정이나 삭제 방식을 변경하지 않는다.
2. `EvidenceAsset.objectVersion`은 실제 S3 version ID가 있으면 이를 저장할 수 있지만, 현행 W02에서는 ETag일 수 있다. 따라서 이 값만으로 객체 버전을 고정하거나 삭제 방지를 증명할 수 없다.
3. W06 `EvidenceVerificationRecord`는 특정 시점에 읽은 바이트의 SHA-256과 크기, 관찰 marker를 남긴다. `VERSIONED_MUTABLE`도 과거 버전 조회 가능성만 뜻하며 삭제 방지를 뜻하지 않는다.
4. W02 기본 개인정보 보유기간은 코드상 수신 시각부터 90일이다. 배포 설정은 1일부터 3650일 범위를 허용하지만 법률 또는 고객사별 장기 보존 근거는 확정되지 않았다.
5. W02 삭제 복구가 key 전체의 현재 객체를 대상으로 하는 반면, Object Lock은 개별 version을 보호한다. 기존 버킷에 잠금을 켜면 삭제 복구와 개인정보 파기 동작이 달라지므로 두 저장소를 분리한다.
6. W06은 원본 바이트 검증을, W07은 별도 저장소에 이미 존재하는 version의 잠금 증명과 정책 판정을 담당한다. W07 저장소 인터페이스에는 PUT, COPY, DELETE가 없다.
7. 운영 활성화 전에는 처리 목적과 법적 근거, 고객사별 상한, 철회와 파기 절차, Governance 우회 권한 보유자, Compliance 사용 여부, 보존 저장소 운영 주체가 확정돼야 한다.

## 보존 방식 비교

| 방식 | 삭제 방지 | 개인정보 파기 충돌 | 권한 분리 | W02 영향 | 비용과 복구 | 결론 |
| --- | --- | --- | --- | --- | --- | --- |
| A. W02 객체와 SHA-256 기록 유지 | 실제 version 삭제와 덮어쓰기를 막지 못함 | 기존 파기와 동일 | 기존 권한 공유 | 없음 | 가장 낮지만 원본 복구 보장 없음 | W06 검증 이력으로 유지하되 불변 보존으로 인정하지 않음 |
| B. version ID 유지 | 삭제 marker 뒤 과거 version 조회 가능, version 직접 삭제는 가능 | version별 삭제 확인 필요 | 버킷에 따라 분리 가능 | 기존 버킷 활성화 시 삭제 의미 변경 | 중간 | `VERSIONED_MUTABLE` 근거일 뿐 완료 상태가 아님 |
| C. 별도 비공개 저장소 사본 | 저장소 분리는 가능하나 잠금 없으면 삭제 가능 | 개인정보 중복과 이중 파기 필요 | 가능 | W02와 분리 | 복제 실패 보상과 정합성 비용 증가 | 운영 법률 근거와 복제 설계 전에는 비활성 |
| D. 별도 versioned 저장소와 Object Lock | version ID와 retain-until 동안 직접 삭제 방지 | 잠금 기간에는 즉시 파기 불가 | 검증자와 삭제 권한 분리 가능 | W02 설정 불변 | 비용과 장애 복구가 가장 복잡 | 격리 MinIO 합성 객체로 기술 동작만 검증 |

W07은 D의 검증 기반을 채택한다. 버전 관리만 켜진 저장소는 Object Lock 미지원으로 판정한다. MinIO 전용 SDK를 추가하지 않고 기존 AWS SDK for Java 2.x의 S3 versioning, retention, version GET API를 사용한다.

## 정책 모델과 fail-closed 게이트

`EvidencePreservationPolicy`의 각 행은 수정하는 현재값이 아니라 append-only 정책 버전이다. 업무 `policyId`와 양의 `policyVersion` 조합이 유일하며, 과거 보존 기록은 실행 당시 버전을 계속 참조한다.

정책은 다음 정보를 가진다.

- tenant와 선택적 event 범위
- `DRAFT`, `APPROVED`, `REVOKED`, `EXPIRED` 상태
- 적용 시작과 종료 시각
- 일 단위 보존 상한
- 검증된 저장소 profile
- 허용 모드 `GOVERNANCE` 또는 `COMPLIANCE`
- 승인 주체, 생성·승인·철회 시각

보존 검증은 다음 조건을 모두 만족해야 한다.

1. `ticket-defender.evidence-preservation.enabled=true`
2. 요청 policy의 최신 버전이 서버 DB에 있고 `APPROVED`
3. 승인 주체와 승인 시각이 존재하고 현재 적용 기간 안임
4. 인증된 REVIEWER의 Case tenant와 event가 policy 범위와 일치함
5. Case, Snapshot, Source, EvidenceAsset, 최신 W06 MATCH 기록의 관계가 모두 일치함
6. FanReport와 EvidenceAsset의 원본 보유기간이 지나지 않음
7. policy 저장소 profile과 서버 검증 profile이 일치함
8. 실제 version ID, SHA-256, 크기, Object Lock mode, retain-until을 저장소에서 확인함
9. retain-until이 원본 보유기간, policy 종료 시각, `검증 시각 + retentionLimitDays` 중 가장 이른 시각을 넘지 않음
10. DB 저장 직전에 같은 Case version, source fingerprint, 최신 policy 버전과 유효기간을 다시 확인함

환경 설정만 켜거나 DB에 `APPROVED` 문자열만 넣는 것으로 위 검증을 우회할 수 없다. 다만 DB 관리자 권한은 정책 행과 감사 행을 직접 변경할 수 있다. Hibernate `@Immutable`은 애플리케이션 ORM 수정 방지 장치이지 DB의 물리적 WORM 보장이 아니다. 운영에서는 DB 변경 권한 분리, 변경 감사와 백업 검증이 별도로 필요하다.

## 저장소와 Object Lock 검증

보존 저장소 adapter는 읽기 전용이다. 버킷 versioning과 Object Lock 활성 상태를 먼저 확인한 다음 다음 값을 검증한다.

- S3가 반환한 실제 version ID
- version을 지정한 HEAD와 GET
- 바이트 크기와 스트리밍 SHA-256
- `GetObjectRetention`의 실제 lock mode와 retain-until

ETag 또는 DB 상태만으로 `IMMUTABLE_VERIFIED`를 만들지 않는다. 객체 없음, digest 또는 크기 불일치, version 불일치, 잠금 없음, 모드 불일치, 상한 초과, 권한 부족이나 저장소 장애는 성공 기록을 만들지 않는다.

CI는 기존 W02 버킷과 별도로 `td-issue40-ci-preservation`을 `--with-lock`으로 생성한다. 합성 객체에 짧은 Governance 보존만 적용한다. 보존 검증자는 bucket capability, retention과 version GET만 허용하고 PUT, COPY, DELETE 권한이 없다. 읽기 전용 계정은 retention을 조회할 수 없으며, 테스트 삭제 계정은 version 삭제 권한은 있지만 `BypassGovernanceRetention` 권한이 없다. 관리자 계정만 테스트 정리를 위해 명시적 bypass를 사용한다.

잠긴 version을 직접 삭제하는 요청은 차단되지만, version을 지정하지 않은 DELETE는 delete marker를 만들 수 있다. 이때 현재 key 조회는 가려져도 보호 version은 version ID로 계속 조회된다. 잠금 없는 version은 직접 삭제할 수 있다. Compliance 모드는 CI에서 적용하지 않는다. 삭제 불가능한 장기 객체를 남기지 않기 위해 Compliance 지원과 우회 불가 동작은 미검증으로 보고한다.

## 영속 기록과 감사

`EvidencePreservationRecord`는 저장소 확인이 모두 성공한 경우에만 생성한다. 다음을 함께 고정한다.

- tenant, event, Case, EvidenceSnapshot, CaseSourceReference와 EvidenceAsset
- 원인이 된 W06 EvidenceVerificationRecord
- 업무 policy ID와 실행 policy version
- storage profile, object key의 SHA-256, 실제 version ID
- 객체 SHA-256과 크기, Object Lock mode와 retain-until
- `IMMUTABLE_VERIFIED`, 검증 시각과 actor membership

원본 object key는 일반 응답이나 감사 detail에 저장하지 않는다. DB에는 key의 SHA-256만 남긴다. 같은 tenant, evidence, storage profile, actual version과 policy version 조합은 UNIQUE 제약으로 중복 성공 기록을 막는다.

`EvidencePreservationAttempt`는 인가 후 확인 가능한 명령의 결과를 append-only로 남긴다. 성공은 보존 기록을 참조하고, 정책 거절·저장소 거절·저장소 장애·경합은 보존 기록을 참조하지 않는다. 자격증명, 원본 바이트, raw object key와 예외 전문은 저장하지 않는다.

## 트랜잭션, 멱등성과 장애 복구

객체 HEAD, retention 조회와 GET은 DB 쓰기 트랜잭션 밖에서 실행한다. 성공 직전의 짧은 DB 트랜잭션에서 Case 낙관적 version 증가, 보존 성공 기록, `EVIDENCE_PRESERVATION_VERIFIED` CaseActivity, 성공 시도 감사와 멱등 결과를 함께 커밋한다. 어느 INSERT든 실패하면 이 성공 묶음은 전부 롤백되고, DB 접근 실패는 `DEPENDENCY_UNAVAILABLE`로 반환한 뒤 별도 트랜잭션의 `CONFLICT` 실패 시도로 감사한다.

멱등 key는 tenant, actor, operation, Case 범위에서 digest로 저장한다. 성공한 같은 요청은 저장소를 다시 읽지 않고 같은 보존 기록을 반환한다. 같은 key와 다른 본문은 `IDEMPOTENCY_KEY_REUSED`로 거절한다. 정책 또는 저장소 실패에는 재사용 가능한 멱등 결과를 남기지 않으므로, 원인이 해소되고 Case version이 그대로라면 같은 key로 재시도할 수 있다. 실패 시도 감사는 별도 짧은 트랜잭션으로 남고 성공으로 가장되지 않는다.

외부 GET 중 Case가 바뀌면 `CASE_VERSION_CONFLICT`, source가 바뀌면 `SOURCE_CHANGED`, 최신 policy가 철회 또는 교체되면 정책 거절이나 `PRESERVATION_POLICY_CHANGED`로 성공 커밋을 막는다. 동시에 같은 명령이 들어오면 Case 낙관적 잠금, 성공 UNIQUE 제약과 멱등 replay가 성공 기록과 CaseActivity를 하나로 제한한다.

## 수명주기와 파기

W07은 원본 retention을 늘리지 않는다. 허용 가능한 retain-until의 상한은 원본 FanReport/EvidenceAsset 만료, policy 종료, policy 보존 일수 상한 중 가장 이른 값이다. 이미 만료된 원본, 철회 또는 만료된 policy에는 신규 보존을 허용하지 않는다.

W07은 파기 작업을 구현하지 않는다. 운영 구현에서는 원본 만료, policy 만료, policy 철회, 정상 삭제 가능, 잠금으로 즉시 삭제 불가, 저장소 장애, delete marker만 존재, 실제 보호 version 삭제 완료를 서로 다른 상태로 다뤄야 한다. delete marker 생성만으로 파기 완료를 기록해서는 안 된다.

## 스키마와 검증 범위

`docs/database/case-w07-migration.sql`은 W06 이후 적용하는 additive migration이다. 기존 행을 수정하거나 보존 완료로 전환하지 않는다. tenant/event/Case/Snapshot/source/asset/W06 검증/policy version/actor 관계를 FK로 묶고 성공 중복과 상태 형태를 UNIQUE 및 CHECK로 제한한다.

기본 Java 테스트는 정책 없음과 각 비승인 상태, 기능 비활성, tenant/event/profile 불일치, 자연 만료, 보존 상한, 잠금 미확인·미지원, 저장소 장애 재시도, 인가와 검증 범위, Case와 policy 경합, 성공 원자성과 replay를 검증한다. 격리 MySQL 8 CI는 migration, `ddl-auto=validate`, UNIQUE/FK/CHECK, 동시 멱등, 낙관적 경합과 CaseActivity 실패 롤백을 검증한다. 격리 MinIO CI는 실제 version ID, Governance retention, 보호 version 삭제 차단, delete marker, 잠금 없는 version 삭제, 최소 권한과 Object Lock 미지원 버킷을 검증하고 실제 MySQL 성공 기록까지 연결한다.

## 운영 전 미결정 사항

- 고객사와 증거 유형별 처리 목적, 법적 근거와 보존 상한
- 원본과 보존 사본의 파기 요청 및 예외 처리 책임자
- Governance bypass 권한의 승인, 이중 통제와 감사 방식
- Compliance 모드 사용 여부와 복구 불가능성 수용 절차
- 보존 저장소 지역, 암호화 key, 백업, 비용과 장애 복구 목표
- 운영 정책 생성·승인·철회 API와 권한 분리
- 실제 개인정보 복제의 outbox, 재시도, 보상 및 대사 설계

이 항목들이 승인되고 운영 API와 복제 수명주기가 별도 검토되기 전까지 W07을 운영 개인정보 장기 보존 완료로 표시해서는 안 된다.
