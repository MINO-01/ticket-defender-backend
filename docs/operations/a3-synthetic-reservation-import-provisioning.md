# A3 합성 예매 반입 초기화 절차

이 절차는 A3 격리 MySQL에서만 합성 테넌트 등록과 완전 합성 예매 스냅샷 반입을 준비한다. 공개 API나 운영 고객사 등록을 만들지 않으며, 기본값은 비활성이고 `dry-run`이다.

## 안전 경계

- 실행 파일과 fixture는 저장소 밖의 운영자 전용 경로에 둔다. 실제 runtime 환경 파일, credential, HMAC 값, 원본 예매번호를 Git에 저장하거나 명령행 인수에 넣지 않는다.
- runner는 `SYNTHETIC_ISOLATED`, candidate-import 활성화, loopback datasource, 32자 이상 기존 HMAC key, key version, A3 isolation UUID를 기존 `SyntheticReservationImportGate`로 다시 검증한다.
- 추가로 실제 JDBC 연결이 MySQL이고 configured expected database name과 현재 catalog가 일치하는지 확인한다. 정상 운영 datasource, H2, 원격 MySQL, 다른 schema는 거부한다.
- `tenantId`, `eventId`, `membershipId`, `performanceId`는 서버 데이터로 확인한다. tenant/event/membership은 ACTIVE여야 하며 membership에는 `TENANT_ADMIN` 및 해당 event grant가 있어야 한다. performance도 같은 tenant/event의 ACTIVE 상태여야 한다.
- fixture는 절대 경로의 1 MiB 이하 UTF-8 properties 파일만 허용한다. `fixture-type=A3_SYNTHETIC_RESERVATION_SNAPSHOT_V1`, `complete-snapshot=true`, 1~1000개 행, `SYNTHETIC-` 접두어 reservation reference, 유효한 zone/row/seat가 모두 필요하다. 실제 예매번호는 허용하지 않는다.
- 원본 reference는 기존 `ReservationImportService`에서 tenant 목적별 HMAC으로만 저장한다. runner 로그와 결과에는 ID, fixture path, reference, HMAC, HMAC key를 기록하지 않는다.

## 운영자 전용 설정

사용자 전용 A3 runtime 설정에 아래 환경변수의 **값만** 넣는다. repository의 `.env` 또는 `application-*.yml`에는 실제 값을 두지 않는다.

| 변수 | dry-run | execute |
| --- | --- | --- |
| `A3_SYNTHETIC_IMPORT_PROVISIONING_ENABLED` | `true` | `true` |
| `A3_SYNTHETIC_IMPORT_PROVISIONING_EXECUTE` | `false` | `true` |
| `A3_SYNTHETIC_IMPORT_PROVISIONING_TENANT_ID` | required | required |
| `A3_SYNTHETIC_IMPORT_PROVISIONING_EVENT_ID` | required | required |
| `A3_SYNTHETIC_IMPORT_PROVISIONING_MEMBERSHIP_ID` | required | required |
| `A3_SYNTHETIC_IMPORT_PROVISIONING_ISOLATION_ID` | required | required |
| `A3_SYNTHETIC_IMPORT_PROVISIONING_EXPECTED_DATABASE_NAME` | required | required |
| `A3_SYNTHETIC_IMPORT_PROVISIONING_FIXTURE_PATH` | required, absolute | required, absolute |

기존 candidate-import 환경값(`enabled`, `operational-mode`, `synthetic-isolation-id`, HMAC key/key version)도 별도 A3 보안 설정에 있어야 한다. 이 runner는 키를 새로 만들거나 출력하지 않는다.

## fixture 형식

다음은 자리표시자만 든 형식이다. 실제 대상 UUID와 row 값은 A3 전용 경로의 파일에만 넣는다.

```properties
fixture-type=A3_SYNTHETIC_RESERVATION_SNAPSHOT_V1
complete-snapshot=true
performance-id=<A3_ACTIVE_PERFORMANCE_UUID>
source-version=a3-synthetic-v1
row-count=1
row.0.reservation-reference=SYNTHETIC-A3-RESERVATION-0001
row.0.zone=VIP
row.0.row=10
row.0.seat=5
```

`sourceVersion`은 같은 tenant/event/performance 범위에서 불변이다. 다른 본문으로 같은 version을 재사용하면 충돌로 중단한다. 새 fixture가 필요하면 기존 batch나 candidate를 삭제하지 말고 검증된 새 version을 사용한다.

## 실행과 감사

1. 실행 전에 A3 Spring을 `spring.main.web-application-type=none`으로 시작하도록 준비한다. execute mode는 web application context에서 코드로 거부된다. 다른 서비스와 기존 web Spring 프로세스를 재시작하지 않는다.
2. `EXECUTE=false`로 dry-run을 한 번 실행한다. 성공 로그에는 모드, registration 생성 여부, 행 수, import status만 나온다. dry-run은 DB에 쓰지 않는다.
3. dry-run이 성공한 동일한 운영자 설정에서만 `EXECUTE=true`으로 한 번 실행한다.
4. runner는 tenant row를 잠가 동일 tenant의 동시 등록을 직렬화한다. 등록·반입은 하나의 transaction으로 실행된다. 새 등록 뒤 반입이 실패하면 새 등록도 rollback된다.
5. 이미 같은 isolation ID의 등록이 있으면 재사용한다. 반입은 tenant/event/performance/source version과 결정적 idempotency key를 사용하므로 같은 fixture는 재현되고, 다른 fixture 충돌은 중단된다.
6. 성공 감사는 서버 소유 `synthetic_reservation_import_tenants`, 기존 `reservation_import_batches`, candidate/source tables에 남는다. 결과에는 원본 reference나 HMAC가 없다.

## 실패 및 복구

- `A3_SYNTHETIC_IMPORT_*_UNVERIFIED`, `*_INVALID`, `*_CONFLICT`는 fail-closed 결과다. 환경 또는 fixture를 교정한 뒤 새 dry-run부터 수행한다.
- 기존 registration 또는 ACCEPTED batch는 삭제·수정하지 않는다. 이미 ACCEPTED인 source를 되돌릴 필요가 있으면 운영자를 통해 candidate-import gate를 비활성화하고, 후속 검증된 source version을 반입한다.
- MySQL 통합 검증 및 실제 API `ACCEPTED`/`REJECTED` 확인은 Docker가 복구된 A3 격리 환경에서만 수행한다. 운영 DB, MinIO, 팬 제보, Case에는 이 runner를 사용하지 않는다.
