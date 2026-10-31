# 팬 제보 접수 운영 계약

이 문서는 [팬 제보 OpenAPI](../api/fan-report-intake-v1.yaml)와
`docs/database/fan-report-intake-migration.sql`의 적용 조건을 설명한다.
기본값 `FAN_REPORT_ENABLED=false`이므로 새 공개 경로의 익명 요청은 차단된다.
기존 관리자 JWT 경로, Track 1/2 분석, Case/Approval, 공식 PDF 발급과 연결하지 않는다.

## 범위와 공연 설정

- 요청에는 서버에 등록된 `events.id` UUID만 보낸다. Frontend의 `DEMO_AUTUMN_LIVE` 문자열은 실제 등록 ID가 아니며 다음 Frontend 연동에서 공연 목록 또는 별도 공개 식별자 계약이 필요하다.
- Spring은 `events.id`로 행을 찾고 `events.tenant_id`를 사용한다. 공연과 고객사가 모두 `ACTIVE`이고 `events.public_intake_enabled=1`인 경우만 접수한다. 요청의 `tenantId`와 `performanceRef`는 허용하지 않는다.
- 기존 공연은 마이그레이션 후 `public_intake_enabled=0`이다. 운영자가 고객사와 공연의 사용 권한, 게시할 공개 공연 ID, 개인정보 안내, 요청 제한, 비공개 저장소를 검증한 다음 지정된 이벤트 ID에 대해 `UPDATE events SET public_intake_enabled=1 WHERE id=? AND tenant_id=? AND status='ACTIVE'`를 별도 승인된 DB 변경 절차로 실행한다. 이 문서는 운영 DB에 직접 실행하는 지시가 아니다.
- `performanceAt`은 팬이 입력한 지역 일시이며 예매처가 확인한 `performanceRef`가 아니다. `FanReportScopeService`는 저장된 tenant/event에 검증된 `verifiedPerformanceRef`가 별도 기록되기 전까지 Track 2 `ReportScope`를 반환하지 않는다.
- `fanReportId`는 실제 영속 UUID이며 후속 Case `fanReportIds`의 후보 참조다. `evidenceId`는 해당 제보의 별도 파일 UUID다. 기존 `TicketAudit.id`와 변환하거나 자동 연결하지 않는다. Specs의 이전 `reportId` 응답 초안은 제안 상태였으며 이 API는 `fanReportId`로 확정한다.

## 비공개 저장소와 SDK 선택

AWS SDK for Java 2.x의 S3 클라이언트를 사용한다. [AWS 문서](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3.html)는 `endpointOverride`와 `forcePathStyle(true)`를 제공하므로 MinIO 개발 환경과 다른 S3 호환 서비스에 같은 포트를 사용할 수 있다. [MinIO Java SDK](https://minio-java.min.io/io/minio/MinioClient.html)도 put/delete와 버킷 정책을 제공하지만, 이 코드에서는 별도 전용 SDK 대신 S3 표준 요청을 택했다. 이 선택은 모든 S3 호환 제품의 동작을 보증하지 않는다.

- 전용 버킷을 미리 만들고 비공개 정책으로 설정한다. 서비스 계정에는 버킷 범위의 `s3:ListBucket`과 `fan-reports/*` 객체 범위의 `s3:PutObject`, `s3:DeleteObject`만 허용한다. 어댑터의 `HeadBucket` 호출은 [AWS 권한 문서](https://docs.aws.amazon.com/AmazonS3/latest/API/API_HeadBucket.html)에 따라 `s3:ListBucket`이 필요하다. 서비스 계정에 `s3:GetObject`나 전체 버킷 나열 권한을 부여하지 않는다. 익명 읽기·나열, 공개 ACL, 정적 웹 호스팅을 허용하지 않는다. API는 다운로드 경로와 presigned URL을 만들지 않는다.
- 현재 복구는 객체 키로 삭제하므로 버킷 **versioning과 Object Lock을 비활성화**해야 한다. 각 객체 키는 서버가 새 UUID로 만들고 조건부 PUT으로 덮어쓰기를 거부한다. `object_version`에는 S3 version ID가 있으면 그 값, 없으면 응답 ETag를 기록한다. 실제 증거 동일성은 저장된 고유 객체 키와 원본 SHA-256으로 고정한다.
- 보안 명세 `11_보안_개인정보_위협모델.md`의 서버 측 암호화 요구에 따라 운영 버킷의 기본 암호화와 키 접근 권한을 구성하고 실제 업로드 객체에 적용됐는지 확인한다. 현재 어댑터는 PUT 요청에 암호화 헤더를 지정하지 않으므로 저장소의 기본 설정이 없는 환경에서는 암호화를 보장하지 않는다. 키 관리·교체, 백업, 만료 후 삭제와 법적 보전 절차를 확정하기 전에는 외부 공개 접수를 활성화하지 않는다.
- 개발 MinIO는 기존 MySQL Compose 서비스·포트·볼륨을 변경하지 않고 별도 격리 인스턴스로 준비한다. 2026-10-20에는 별도 Docker 컨테이너의 MinIO와 MySQL 8.0에서 `S3EvidenceObjectStorageMinioTest`, `FanReportMinioMySqlTest`, 마이그레이션 및 JPA validate 테스트를 실행했다. 전용 비공개 버킷에서 JPEG·PNG 업로드와 원본 SHA-256, 익명 GET·나열 거부, 잘못된 자격증명 거부, 최소 권한 `HeadBucket`, 조건부 PUT 덮어쓰기 거부, 장애 후 객체 정리와 완료 제보 객체 보존을 확인했다. 버킷 versioning은 비활성 상태였으나 기본 서버 측 암호화 설정은 없었다. 이 테스트 환경의 성공만으로 운영 버킷 암호화나 외부 공개 조건이 충족됐다고 판단하지 않는다. 운영에서는 TLS endpoint, 비밀 관리 저장소, 기본 암호화와 실제 객체 암호화 확인, Object Lock 및 versioning 비활성 상태, 보유기간 삭제 절차를 별도로 검증한다.
- `FAN_REPORT_ENABLED=true`에는 최소 32바이트 난수를 Base64로 인코딩한 `FAN_REPORT_HMAC_KEY_BASE64`가 필수다. 예시나 테스트 키를 운영에 사용하지 않는다. 이 키는 MySQL 요청 제한의 IP 식별자 HMAC에만 사용하며 비밀 관리 저장소에서 주입한다. 누락되면 공개 접수 애플리케이션 시작이 실패한다. 키 교체 뒤 기존 요청 제한 식별자는 다시 계산되므로 게이트웨이 제한을 함께 유지하고 교체 시도를 운영 기록에 남긴다. 신규 제보의 멱등·조회 검증값은 이 키에 의존하지 않는다.
- 클라이언트는 첫 요청 전에 암호학적 난수 기반 UUIDv4 `Idempotency-Key`와 **독립적인 32바이트 난수** `Report-Recovery-Secret`을 생성한다. 후자는 패딩 없는 Base64URL 43자로 인코딩해 헤더로 보내고, 두 값 모두 접수번호와 조회 코드를 안전하게 보관할 때까지 비공개로 유지한다. 브라우저에서는 `crypto.randomUUID()`와 `crypto.getRandomValues(new Uint8Array(32))`를 사용한다. 서버는 길이와 인코딩을 검사하지만 난수 생성기의 품질은 검증할 수 없으므로 클라이언트 구현 점검이 필요하다. 프록시와 애플리케이션의 요청 헤더·응답 본문 로깅에서 두 값과 조회 코드를 제외한다.

## 저장·재시도·복구

1. 신규 접수에서 유효한 공연과 이미지 내용을 확인하고 버킷 연결을 확인한다. 완료된 제보의 재시도에서는 동일 요청과 복구 자격을 검증한다.
2. `fan_reports`와 `evidence_assets`의 `PENDING_STORAGE`/`PENDING` 행 및 서버 생성 객체 키를 한 MySQL 트랜잭션에 저장한다.
3. 비공개 객체를 쓰고 마지막에 DB를 `RECEIVED`/`STORED`로 확정한다. 객체 쓰기 또는 DB 최종화 실패 시 저장된 키를 삭제하고 `STORAGE_FAILED`/`FAILED`로 기록한다. 삭제나 DB 갱신이 실패하면 30분 이상 된 미완료 행을 10분마다 재시도한다. 따라서 실패 응답을 정상 접수로 취급하지 않는다.
4. 동일 `Idempotency-Key`, 동일 `Report-Recovery-Secret`, 동일 메타데이터와 동일 순서의 이미지 바이트는 완료 후 같은 접수번호와 조회 코드를 200으로 반환한다. 완료된 제보의 재시도에는 공연 접수 허용 상태와 객체 저장소 준비 상태를 다시 요구하지 않는다. 신규 접수에만 두 조건을 적용하고 DB 예약 시 공연 범위를 잠금으로 재검증한다. 서버는 UUIDv4 멱등 키의 SHA-256 검증값, 복구 비밀값으로 HMAC 처리한 요청 검증값, 조회 코드의 SHA-256 검증값만 저장한다. 조회 코드는 복구 비밀값과 멱등 키, 접수번호로 재현하고 저장된 검증값과 상수 시간으로 비교한다. 잘못된 복구 비밀값은 접수번호를 공개하지 않고 404, 올바른 비밀값과 다른 내용은 409, 진행 중이면 409 `INTAKE_IN_PROGRESS`, 영속된 저장 실패 기록이 확인되면 503 `INTAKE_STORAGE_UNAVAILABLE`이다. 첫 응답이 유실되면 보관해 둔 두 자격값으로 동일 요청을 재전송한다. 저장 실패 후 다시 접수하려면 새 키와 새 비밀값을 사용한다. 서버는 새 키를 대신 만들지 않는다. 이전 알고리즘으로 저장된 행은 기존 서버 키가 유지되는 동안 이미 발급된 코드로 상태 조회할 수 있지만 코드를 재현할 수는 없다. 이전 멱등 키를 재전송하면 중복 접수 대신 409 `LEGACY_RECOVERY_UNAVAILABLE`를 반환한다. 해당 행이 존재한 배포에서는 서버 키를 교체하거나 공개 접수를 다시 활성화하기 전에 행의 이행 방식을 별도로 확정한다.
5. 503 오류는 재시도 정책에 따라 코드를 구분한다. `INTAKE_STORAGE_UNAVAILABLE`은 같은 키의 `STORAGE_FAILED` 행이 DB에 기록된 것을 확인한 경우에만 반환하며, 그 키로는 다시 성공할 수 없다. `INTAKE_OUTCOME_UNKNOWN`과 `Retry-After: 5`는 요청 제한 테이블 잠금이나 일시적인 DB 조회 장애, 실패 상태 기록 실패, `markReceived` 최종 커밋 응답 유실처럼 결과를 확정할 수 없는 경우에 반환한다. 이때는 같은 키와 복구 비밀값, 같은 본문으로 다시 요청한다. 재요청 결과는 접수 완료면 같은 접수 정보의 200, 처리 중이면 409, 정리 작업이 실패를 확정하면 503 `INTAKE_STORAGE_UNAVAILABLE`이며 새 접수를 만들지 않는다.
6. 최종 커밋 실패 후에는 잠금 정리(`cleanStale`) 뒤 같은 행을 다시 읽어 상태를 판정한다. `RECEIVED`가 확인되면 정상 접수 영수증을 반환하고, `STORAGE_FAILED`가 확인되면 확정 실패로 응답한다. 다시 읽기도 실패하면 업로드된 원본을 지우지 않고 `INTAKE_OUTCOME_UNKNOWN`으로 응답하며, 미완료 행은 기존 정리 작업이 처리한다. 결과 불확실을 해결하려고 저장 데이터를 삭제하거나 DB 설정을 완화하지 않는다.
7. 고유 DB 제약이 동시 동일 키의 중복 제보 생성을 막는다. 전송 중 프로세스가 종료되면 먼저 저장한 객체 키를 기준으로 정리한다. 삭제 실패를 무시하고 고아 파일이 없다고 가정하지 않는다.

## 남용 방지와 개인정보 안내

- 서버는 실제 bytes 및 ImageIO 디코딩으로 JPEG/PNG를 검사한다. 최대 5개, 파일당 20MiB, 합계 100MiB, 픽셀 2천만 개, 한 변 12,000픽셀을 제한한다. Servlet multipart 전체 요청은 101MB다. 파일 이름은 객체 키에 반영하지 않는다. 디코더는 메모리 캐시를 사용한다.
- 공개 접수의 POST 요청은 Multipart 파싱 전 필터에서 JVM별 동시 슬롯을 비차단 방식으로 확보한다. `FAN_REPORT_MAX_CONCURRENT_INTAKES=0` 기본값은 JVM 최대 힙에서 기본 여유 256MiB와 요청당 256MiB를 고려해 최대 4개까지 산정한다. 힙이 512MiB 미만이거나 설정값이 산정 한도를 넘으면 공개 접수 시작을 거부한다. 슬롯이 없거나 요청 스레드가 중단되면 본문 파싱 전에 503 `INTAKE_BUSY`와 `Retry-After: 1`을 반환하고, 요청 완료 또는 예외 시 슬롯을 반환한다. 이미지 없는 multipart 제보도 같은 진입 제한을 거쳐 정상 처리된다. 이 제한은 프로세스별이며 프록시의 업로드 연결 수·전체 바이트 제한을 대체하지 않는다. Servlet 크기 초과는 컨트롤러 선택 전에도 413 `REQUEST_TOO_LARGE` ProblemDetail로 반환한다.
- MySQL `public_request_quota`가 서버에서 관측한 원격 IP의 접수를 분당 2건·시간당 20건으로, 조회를 IP별 분당 10건·시간당 100건과 접수번호별 분당 5건으로 제한한다. 여러 Spring 인스턴스가 같은 MySQL 행을 사용하고 48시간 지난 식별자 행은 주기적으로 삭제한다. 역방향 프록시는 신뢰된 클라이언트 주소만 `remoteAddr`로 전달해야 하며 임의 `X-Forwarded-For`는 신뢰하지 않는다. 외부 배포에는 프록시의 전체 요청 바이트·동시 업로드·소스별 분산 제한, TLS, 이미지 악성코드 검사와 모니터링도 필요하다. 이 운영 게이트가 없으면 `FAN_REPORT_ENABLED=false`를 유지한다.
- 처리 목적: 의심 거래 제보 접수와 근거 보존·조사 준비. `FAN_REPORT_RETENTION_DAYS` 기본 90일을 `fan_reports.retention_until`과 `evidence_assets.retention_until`에 기록한다. 자동 삭제는 구현하지 않았으므로 만료 검토, Case/법적 보전 여부 확인, MySQL 및 객체의 통제된 삭제·감사 절차를 운영 전에 별도로 마련한다. 보유기간을 무제한으로 해석하지 않는다.
- 이 접수 경로는 이미지를 Gemini, FastAPI 또는 다른 제3자에게 전송하지 않는다. 제3자 제공·외부 분석·국외 이전을 시작하려면 고객사별 법적 근거, 고지와 위탁 조건을 먼저 확정해야 한다. 임의의 동의 완료 상태를 만들지 않는다. 사용자 안내에는 목적, 보유 기간, 담당자 연락·열람/삭제 절차, 제3자 제공 여부, 외부 분석 여부를 실제 운영 정책에 맞게 표시한다.
- 제보 원문, 조회 코드, 객체 키, 업로드 bytes, HMAC 키는 로그에 넣지 않는다. 조회 응답은 `intakeStatus`와 UTC 접수 시각만 담는다. `RECEIVED`는 접수 완료일 뿐 분석 완료나 부정판매 판정이 아니다.
