# 대량 CSV v3 원본 신호 수신 운영 문서

상태: 합성 격리 환경 전용. 실제 예매처 tenant에 여는 것은 별도 계약과 법적 검토 후의 운영 설정이며
이 문서의 범위가 아니다.

## 목적과 경계

- 예매처는 자기 시스템에서 내려받은 원본 값을 CSV 하나로 올리고, 백엔드는 행을 읽는 즉시 정규화하고
  신호별 목적의 HMAC으로 바꾼다. 원본 값은 DB, 로그, 오류 응답에 남기지 않는다.
- v3 원본 수신은 기존 합성 격리 게이트(`requireSyntheticScope`) 아래에서만 동작한다.
- v1, v2의 헤더 검사와 상태 전이는 바뀌지 않는다. v2는 계속 모르는 열을 `BULK_CSV_HEADER_INVALID`로
  거절한다.
- 점수는 조사 우선순위일 뿐이며 원본 신호가 같다는 사실이 동일인이나 위반을 뜻하지 않는다.

## 설정

| 키 | 기본값 | 의미 |
| --- | --- | --- |
| `ticket-defender.bulk-ledger-upload.v3-raw-intake.enabled` | `false` | v3 등록, 업로드, 스테이징 허용 |
| `ticket-defender.bulk-ledger-upload.cleanup.enabled` | `false` | 보존 한도를 넘긴 업로드 원본 정리 작업 |
| `ticket-defender.bulk-ledger-upload.cleanup.retention` | `PT24H` | 보존 한도. 0보다 크고 24시간 이하만 허용 |
| `ticket-defender.bulk-ledger-upload.cleanup.delay` | `PT1H` | 정리 작업 주기 |
| `ticket-defender.track1-signal-hmac.key-source` | `SYNTHETIC_CONFIG` | 신호 HMAC 키 출처. `SYNTHETIC_CONFIG` 또는 `KMS` |
| `ticket-defender.track1.ip-network-classes` | 빈 값 | `CIDR=망유형` 목록. 비어 있으면 모두 `UNKNOWN` |

시작 조건:

1. v3를 켜면서 정리 작업을 끄면 애플리케이션이 시작하지 않는다.
2. v3가 켜져 있으면 시작할 때 서버 전용 점검 객체를 만들고 그 버전을 실제로 지울 수 있는지 확인한다.
   확인되지 않으면 v3 요청만 `503 BULK_V3_DELETE_PERMISSION_UNVERIFIED`로 거절하고 v1, v2는 그대로
   동작한다. v3가 꺼져 있으면 `422 BULK_V3_RAW_INTAKE_DISABLED`다.
3. `KMS` 키 출처는 운영 측이 `Track1SignalKeySource` 구현을 등록해야 한다. 구현이 없으면
   `TRACK1_SIGNAL_KEY_UNAVAILABLE`로 실패한다. `SYNTHETIC_CONFIG`는 합성 격리 게이트의 설정 키를 쓰며
   개발과 테스트 전용이다. 연락처와 계좌번호는 값의 범위가 좁아 키가 유출되면 대입으로 원본을 찾을 수
   있으므로 운영 키는 KMS 또는 HSM에서만 받는다. 키 버전을 바꾸면 이전 버전 토큰과는 연결되지 않는다.

## 업로드 원본 삭제

- 스테이징 성공 또는 확정 거절 직후 업로드 객체의 모든 버전과 delete marker를 버전 ID 지정 삭제로 지운다.
  버전 관리 버킷에서 delete marker만 남기면 원본 바이트가 남기 때문이다.
- 최대 3회 시도하고 결과를 `td_bulk_import_upload_deletions`에 기록한다. 삭제 실패는 작업 상태 전이를
  바꾸지 않는다. 재시도 가능 실패(`FAILED_RETRYABLE`)에서는 원본이 다시 필요하므로 지우지 않는다.
- 정리 작업은 보존 한도를 넘긴 미처리, 만료, 삭제 실패 업로드를 다시 지운다. 보존 한도를 넘겨 지워진
  미처리 업로드는 처리할 수 없다. 기존 상태 전이를 바꾸지 않았으므로 그 작업은 저장소 조회 실패로
  재시도 가능 실패 상태에 머물며, 운영자가 취소한 뒤 새로 등록해야 한다.
- 같은 정책을 v1, v2 업로드에도 적용한다.
- 파일 SHA-256, 바이트 수, 행 수는 `td_bulk_import_jobs`에 계속 남아 출처 증명에 쓰인다.
- 3회 시도 후에도 지우지 못한 건수는 `GET /api/v1/admin/ledger-imports/upload-deletions/status`의
  `failedAfterRetriesCount`로 확인한다. 응답에는 객체 키, 버전 ID, 원본 값이 없다.

### 필요한 저장소 권한 변경안 (적용하지 않음)

현재 CI 정책 `.github/scripts/minio-bl02-upload-policy.json`은 이 작업에서 수정하지 않았다. 버전 단위
삭제와 시작 시 점검에는 아래 권한이 더 필요하다. 사람이 검토한 뒤 별도 변경으로 적용한다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:ListBucket", "s3:ListBucketVersions"],
      "Resource": ["arn:aws:s3:::<업로드 버킷>"]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:GetObjectVersion", "s3:PutObject", "s3:DeleteObject", "s3:DeleteObjectVersion"],
      "Resource": ["arn:aws:s3:::<업로드 버킷>/bulk-ledger-private/*"]
    }
  ]
}
```

이 권한이 없으면 시작 점검이 실패해 v3는 닫혀 있고, v1, v2 삭제 시도는 `FAILED`로 기록된다.

### 버킷 수명 주기 규칙 예시 (문서 전용)

애플리케이션 삭제가 실패해도 원본이 오래 남지 않도록 업로드 접두에 수명 주기 규칙을 함께 둔다.
운영 버킷을 직접 바꾸지 않으며 아래는 예시다.

```json
{
  "Rules": [{
    "ID": "bulk-ledger-upload-expire",
    "Status": "Enabled",
    "Filter": { "Prefix": "bulk-ledger-private/" },
    "Expiration": { "Days": 1 },
    "NoncurrentVersionExpiration": { "NoncurrentDays": 1 },
    "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 1 }
  }]
}
```

## v3 열과 정규화

v3 = v2 필수 열 전부 + 아래 선택 열. 열이 없거나 값이 비면 그 신호는 없음이다. 필드 512자, 행 8KiB 제한은
그대로다.

| 열 | 정규화 | 신호 목적 |
| --- | --- | --- |
| `device_reference` | 앞뒤 공백 제거 | `TRACK1_SIG_DEVICE` |
| `ip_reference` | 점으로 구분된 10진수 IPv4만 허용, 앞자리 0 거절 | `TRACK1_SIG_IP` |
| `ip_network_type` | 허용 목록 검사 후 사용(`DECLARED`). 없으면 CIDR 표(`CLASSIFIED`), 둘 다 없으면 `UNKNOWN` | 없음 |
| `delivery_address` | NFKC, 괄호 안 참고항목 제거, 연속 공백 하나로 압축 | `TRACK1_SIG_ADDRESS` |
| `recipient_phone`, `member_phone` | 숫자만, 국가번호 `+82`는 `0`으로 | `TRACK1_SIG_PHONE` |
| `payment_masked_card` | 공백과 하이픈 제거 | `TRACK1_SIG_PAYMENT` |
| `payment_issuer_code`, `payment_method_type` | 대문자 | `TRACK1_SIG_PAYMENT` |
| `refund_bank_code`, `refund_account_number` | 숫자만 | `TRACK1_SIG_REFUND_ACCOUNT` |

- 결제 복합키는 `masked_card|issuer_code|method_type`, 환불 계좌는 `bank_code|account_number`다.
  세 결제 열이나 두 환불 열 중 일부만 오면 행 오류다.
- 연락처 신호는 수령인 연락처가 있으면 수령인, 없으면 회원 연락처이며 출처(`RECIPIENT`, `MEMBER`)를 기록한다.
- 같은 원본은 같은 토큰이고, 같은 문자열이라도 신호 종류가 다르면 다른 토큰이다.

행 오류 코드(행 번호와 코드만 남는다): `BULK_CSV_IP_INVALID`, `BULK_CSV_IP_NETWORK_TYPE_INVALID`,
`BULK_CSV_PAYMENT_UNMASKED`, `BULK_CSV_PAYMENT_INCOMPLETE`, `BULK_CSV_REFUND_ACCOUNT_INCOMPLETE`,
`BULK_CSV_PHONE_INVALID`, `BULK_CSV_ADDRESS_INVALID`. 따옴표 오류 같은 CSV 문법 오류는 모든 버전에서
`BULK_CSV_INVALID_UTF8_OR_SYNTAX`로 확정 거절한다(이전에는 재시도 가능 실패로 분류됐다).

## 공개와 신호 저장

- 공개 트랜잭션에서 v3 스테이징 신호를 `td_bulk_ledger_track1_ticket_signals`에 공개 버전 ID로 기록한다.
  행 단위 `hmac_purpose`는 BL-07 검사와 맞도록 `TRACK1_CONNECTION`이고, 키 버전은 스테이징 키 버전이다.
- 티켓마다 가장 늦은 원장 행(source_sequence, 행 번호 순)만 쓰고 REMOVE 행은 제외한다.
- 기존 HMAC 신호 등록 API로 같은 티켓에 다른 값이 오면 `LEDGER_SIGNAL_ALREADY_RECORDED`다.

## migration 적용 순서

로컬 MySQL80과 기존 DB에는 적용하지 않는다. 일회용 CI MySQL 8에서만 검증했다.

1. `bulk-ledger-import-bl01` ~ `bl05` (기존)
2. `bulk-ledger-import-v3-raw-signal-migration.sql` (스테이징 신호 컬럼, 업로드 삭제 기록)
3. `bulk-ledger-import-bl06-track1-outbox-migration.sql`, `bulk-ledger-track1-bulk-job-migration.sql`,
   `bulk-ledger-track1-auxiliary-results-migration.sql` (기존)
4. `bulk-ledger-score-v2-exp2-signal-migration.sql`과 MEI 체인
   (`multi-event-investigation`, `multi-event-quantity-snapshot`, `multi-event-source-binding`,
   `multi-event-artifact-job`) (기존)
5. `bulk-ledger-track1-signal-source-migration.sql` (신호 출처 컬럼)
6. `multi-event-multi-performance-migration.sql` (다회차 점수 버전과 후보 CHECK)
