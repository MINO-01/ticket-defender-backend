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

- 전용 버킷을 미리 만들고 비공개 정책으로 설정한다. 서비스 계정에는 그 버킷의 `HeadBucket`, `PutObject`, `DeleteObject`만 허용한다. 익명 읽기, 공개 ACL, 정적 웹 호스팅을 허용하지 않는다. API는 다운로드 경로와 presigned URL을 만들지 않는다.
- 현재 복구는 객체 키로 삭제하므로 버킷 **versioning과 Object Lock을 비활성화**해야 한다. 각 객체 키는 서버가 새 UUID로 만들고 조건부 PUT으로 덮어쓰기를 거부한다. `object_version`에는 S3 version ID가 있으면 그 값, 없으면 응답 ETag를 기록한다. 실제 증거 동일성은 저장된 고유 객체 키와 원본 SHA-256으로 고정한다.
- 개발 MinIO는 기존 MySQL Compose 서비스·포트·볼륨을 변경하지 않고 별도 격리 인스턴스로 준비한다. 현재 저장소에서 사용할 수 있는 검증된 MinIO 이미지와 실행 중인 Docker daemon이 없어 Compose 서비스는 추가하지 않았다. 설치된 MinIO를 사용할 때 전용 버킷을 private으로 생성하고 `FAN_REPORT_S3_ENDPOINT=http://127.0.0.1:9000`, `FAN_REPORT_S3_BUCKET`, `FAN_REPORT_S3_ACCESS_KEY`, `FAN_REPORT_S3_SECRET_KEY`를 개발 환경에만 설정한다. 외부 배포에서는 TLS endpoint와 비밀 관리 저장소를 사용한다.
- `FAN_REPORT_ENABLED=true`에는 최소 32바이트 난수를 Base64로 인코딩한 `FAN_REPORT_HMAC_KEY_BASE64`도 필수다. 예시나 테스트 키를 운영에 사용하지 않는다. 이 키는 접수 멱등 키, 조회 코드 및 요청 제한 식별자의 도메인 분리 HMAC에 사용한다. 원문은 DB에 저장하지 않는다. 보고서 보유 기간 동안 키를 보관해야 조회할 수 있으므로 키 교체에는 구키 검증 이행이 선행돼야 한다. 현재 자동 키 교체는 없다.

## 저장·재시도·복구

1. 유효한 공연과 이미지 내용을 확인하고 버킷 연결을 확인한다.
2. `fan_reports`와 `evidence_assets`의 `PENDING_STORAGE`/`PENDING` 행 및 서버 생성 객체 키를 한 MySQL 트랜잭션에 저장한다.
3. 비공개 객체를 쓰고 마지막에 DB를 `RECEIVED`/`STORED`로 확정한다. 객체 쓰기 또는 DB 최종화 실패 시 저장된 키를 삭제하고 `STORAGE_FAILED`/`FAILED`로 기록한다. 삭제나 DB 갱신이 실패하면 30분 이상 된 미완료 행을 10분마다 재시도한다. 따라서 실패 응답을 정상 접수로 취급하지 않는다.
4. 동일 `Idempotency-Key`와 동일 메타데이터·동일 순서의 이미지 바이트는 완료 후 같은 접수번호에 200을 반환하지만 조회 코드 원문은 재발급하지 않는다(`lookupCode: null`). 다른 내용은 409, 아직 진행 중이면 409, 저장 실패 기록이면 503이다. 저장 실패 후 다시 접수하려면 새 키를 사용한다. 클라이언트는 첫 201 응답의 코드를 안전하게 보관한다.
5. 고유 DB 제약이 동시 동일 키의 중복 제보 생성을 막는다. 전송 중 프로세스가 종료되면 먼저 저장한 객체 키를 기준으로 정리한다. 삭제 실패를 무시하고 고아 파일이 없다고 가정하지 않는다.

## 남용 방지와 개인정보 안내

- 서버는 실제 bytes 및 ImageIO 디코딩으로 JPEG/PNG를 검사한다. 최대 5개, 파일당 20MiB, 합계 100MiB, 픽셀 2천만 개, 한 변 12,000픽셀을 제한한다. Servlet multipart 전체 요청은 101MB다. 파일 이름은 객체 키에 반영하지 않는다. 디코더는 메모리 캐시를 사용한다.
- MySQL `public_request_quota`가 서버에서 관측한 원격 IP의 접수를 분당 2건·시간당 20건으로, 조회를 IP별 분당 10건·시간당 100건과 접수번호별 분당 5건으로 제한한다. 여러 Spring 인스턴스가 같은 MySQL 행을 사용하고 48시간 지난 식별자 행은 주기적으로 삭제한다. 역방향 프록시는 신뢰된 클라이언트 주소만 `remoteAddr`로 전달해야 하며 임의 `X-Forwarded-For`는 신뢰하지 않는다. 외부 배포에는 프록시의 전체 요청 바이트·동시 업로드·소스별 분산 제한, TLS, 이미지 악성코드 검사와 모니터링도 필요하다. 이 운영 게이트가 없으면 `FAN_REPORT_ENABLED=false`를 유지한다.
- 처리 목적: 의심 거래 제보 접수와 근거 보존·조사 준비. `FAN_REPORT_RETENTION_DAYS` 기본 90일을 `fan_reports.retention_until`과 `evidence_assets.retention_until`에 기록한다. 자동 삭제는 구현하지 않았으므로 만료 검토, Case/법적 보전 여부 확인, MySQL 및 객체의 통제된 삭제·감사 절차를 운영 전에 별도로 마련한다. 보유기간을 무제한으로 해석하지 않는다.
- 이 접수 경로는 이미지를 Gemini, FastAPI 또는 다른 제3자에게 전송하지 않는다. 제3자 제공·외부 분석·국외 이전을 시작하려면 고객사별 법적 근거, 고지와 위탁 조건을 먼저 확정해야 한다. 임의의 동의 완료 상태를 만들지 않는다. 사용자 안내에는 목적, 보유 기간, 담당자 연락·열람/삭제 절차, 제3자 제공 여부, 외부 분석 여부를 실제 운영 정책에 맞게 표시한다.
- 제보 원문, 조회 코드, 객체 키, 업로드 bytes, HMAC 키는 로그에 넣지 않는다. 조회 응답은 `intakeStatus`와 UTC 접수 시각만 담는다. `RECEIVED`는 접수 완료일 뿐 분석 완료나 부정판매 판정이 아니다.
