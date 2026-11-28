# 조사 대상 참조번호 보호 및 열람

## 목적

다공연 후보 또는 대량 분석 군집의 구성원을 권한 있는 담당자가 확인할 때만 계정 ID, 예매번호, 티켓번호를 확인할 수 있게 한다. 분석 점수나 후보 구성은 이 기능에서 변경하지 않는다.

## 저장 항목

- 계정 ID, 예매번호, 티켓번호 세 값만 새 테이블에 AES-256-GCM으로 암호화한다.
- 연락처, 주소, 카드 정보, 환불 계좌, IP 주소는 복호화 가능한 형태로 저장하지 않는다. 기존의 목적별 HMAC 처리만 유지한다.
- 원본 참조번호는 CSV 파싱 중 즉시 암호화한다. 암호화가 비활성화됐거나 선택한 키 제공자가 준비되지 않으면 암호문을 만들지 않고 기존 업로드 처리를 유지한다.
- 각 행에 새 96비트 난수를 nonce로 사용하고, 테넌트, 원장 범위, 행 번호, HMAC 식별자를 GCM 추가 인증 데이터로 묶는다. 암호문을 다른 행이나 테넌트에 옮기면 인증에 실패한다.
- 합성 설정 키는 `INVESTIGATION_REVEAL_SYNTHETIC_MASTER_KEY_BASE64`에서만 읽으며 참조 HMAC 키와 별개다. 테넌트 ID로 분리한 AES 데이터 키를 만든다. 운영 KMS 연결은 `InvestigationRevealKeySource` 구현을 별도로 제공해야 한다. 구현이 없으면 기능은 사실상 꺼진다.
- 현재 키 버전과 저장된 버전이 다르면 복호화를 거부한다. 키 로테이션 전에는 기존 암호문을 복호화할 수 있는 키 보존 정책을 먼저 마련해야 한다.

## 기능 설정

기본 설정은 꺼짐이다.

```text
INVESTIGATION_REVEAL_ENABLED=false
INVESTIGATION_REVEAL_KEY_SOURCE=SYNTHETIC_CONFIG
INVESTIGATION_REVEAL_SYNTHETIC_MASTER_KEY_BASE64=
INVESTIGATION_REVEAL_SYNTHETIC_KEY_VERSION=SYNTHETIC_V1
```

암호문 테이블을 사용하는 환경에는 먼저 `docs/database/investigation-target-reveal-migration.sql`을 적용해야 한다. PR 7 이전 스키마에서는 기능을 켜지 않는다. 이 저장소에는 KMS 키 제공 구현이 포함되어 있지 않다.

## 권한과 API

- 다공연 후보 또는 대량 분석이 완료된 범위만 조회한다.
- `TENANT_ADMIN`과 `ANALYST`만 볼 수 있다. 조회하는 실행의 모든 공연에 같은 활성 소속과 테넌트 권한이 있어야 한다. 다른 테넌트, 공연 또는 허용되지 않은 역할은 404로 숨긴다.
- `GET .../targets`는 계정, 예매, 티켓번호를 끝 두 자리만 남겨 마스킹해 반환한다(예: `****34`). 열람 사유는 `X-Investigation-Reason` 헤더로 받고, 이 응답에도 `Cache-Control: no-store`를 붙인다.
- `POST .../targets/reveal`과 `POST .../targets/export`는 요청 본문의 필수 사유를 최대 500자로 제한한다. 실제 값은 권한 확인 후 `no-store` 응답으로만 반환한다.
- 조사 대상은 요청당 최대 100개씩 페이지로 반환하며, 내보내기는 최대 500개다. 좌석 정보와 PR 1 구성원별 근거가 현재 저장되어 있지 않으면 빈 값으로 반환한다.
- 대상 응답과 CSV는 `purchasedAt`/`purchased_at`(구매 기록 시각)과 `reservedAt`/`reserved_at`(예약 생성 시각)을 별도로 제공한다. `reserved_at`이 없으면 `reservedAt`은 비어 있으며 구매 시각으로 대체하지 않는다. 화면에서 명세의 “예매 시각”을 표시할 때는 `reservedAt`을 사용하고, 없으면 미제공으로 표시한다.
- CSV 내보내기는 응답 메모리에서 생성하고 서버 파일로 쓰지 않는다.
- 다공연 경로는 `/api/v1/admin/events/{eventId}/multi-event/{runId}/candidates/{candidateId}/targets`다. 대량 분석 경로는 `/api/v1/admin/events/{eventId}/ledger-track1/performances/{performanceId}/bulk-analysis-jobs/{jobId}/clusters/{clusterId}/targets`다. 각 경로에 `/reveal` 및 `/export`를 추가한다.

## 감사

마스킹 목록, 실제 값 열람, CSV 내려받기마다 이벤트, 검증된 관리자 subject, 후보 또는 군집, 작업 종류, 대상 수, 사유, 시각을 기록한다. 요청 사유에 현재 응답 대상의 참조번호가 포함되면 감사 저장 전에 해당 문자열을 `[redacted]`로 바꾼다. 원래 참조번호, HMAC, 암호문과 키는 감사 행에 기록하지 않는다.

## 보관과 삭제

- 암호문과 열람 감사 기록은 테넌트 삭제 시 직접 외래 키의 `ON DELETE CASCADE`로 함께 삭제된다. 암호문은 원장 버전, 공연, 업로드 작업 삭제에도 연결된 외래 키를 통해 정리된다.
- 새 원장 버전이 공개되어 이전 publication head를 대체하면 이전 버전의 암호문만 같은 트랜잭션 안에서 제거한다. 과거 분석 이력과 HMAC 원장 행은 이 동작으로 삭제하지 않는다.
- 준비가 거절된 업로드의 미연결 암호문은 지운다. 업로드 원본 삭제 흐름은 기존 BL-02 처리를 유지한다.
- 테넌트 종료 시 공연 및 원장 삭제 절차에 암호문과 조사 열람 감사 기록을 포함한다.

## 합성 대응표 CLI

계정 HMAC과 합성 계정 ID 대응표는 웹 API로 제공하지 않는다. 명시적으로 다음 설정을 켠 비웹 실행만 허용하며, 합성 격리 등록과 합성 키 출처를 다시 확인한다.

```text
SPRING_MAIN_WEB_APPLICATION_TYPE=none
INVESTIGATION_REVEAL_SYNTHETIC_MAP_EXPORT_ENABLED=true
INVESTIGATION_REVEAL_SYNTHETIC_MAP_EXPORT_TENANT_ID=<합성 테넌트 UUID>
INVESTIGATION_REVEAL_SYNTHETIC_MAP_EXPORT_EVENT_ID=<합성 공연 UUID>
INVESTIGATION_REVEAL_SYNTHETIC_MAP_EXPORT_OUTPUT=<저장소 밖의 새 파일 경로>
```

애플리케이션을 `--spring.main.web-application-type=none`으로 실행해야 한다. 기존 출력 파일은 거부하며, 출력 디렉터리 ACL이 현재 사용자, SYSTEM, Administrators 외의 접근을 허용하면 실행을 중단한다.

경로는 기존의 접근 제한된 비공개 디렉터리여야 한다. 기존 파일은 덮어쓰지 않는다. 실행 감사에는 대응표 원문을 넣지 않는다. 실제 실험 또는 정답 파일은 이 기능으로 내보내거나 사용하지 않는다.

## 검증 및 운영 전 확인

- 합성 참조번호의 암호화와 왕복 복호화, nonce 재사용 방지, 테넌트 AAD 격리, 변조 탐지, 키 버전 오류를 검사한다.
- 기본 비활성 상태에서 암호문 테이블에 신규 행이 생기지 않는지 확인한다.
- 404 권한 은닉, 사유 누락 400, no-store, 감사 기록, 원장 교체 삭제, 로그 내 원문 부재를 확인한다.
- 개인정보 처리 목적, 보관기간, 권한 운영, 위탁 계약 문구와 법적 근거는 연구/서비스 시작 전에 담당자의 법무 및 개인정보 보호 검토를 받아야 한다. 이 문서는 법률 판단이나 연구윤리 승인을 대신하지 않는다.

위탁 계약 문안 예시: “수탁자는 부정 이용 방지를 위한 조사 대상 식별 목적으로 회원 아이디, 예매번호, 티켓번호를 암호화하여 보관하며, 위탁자의 권한 있는 담당자에게만 열람을 제공하고 열람 기록을 보관합니다. 연락처, 주소, 결제 정보, 접속 IP는 복원할 수 없는 값으로만 보관합니다.”
