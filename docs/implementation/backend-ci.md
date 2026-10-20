# Backend PR CI 검증 범위

[Backend CI 워크플로](../../.github/workflows/backend-ci.yml)는 PR, main Push, 수동 실행에서 다음 네 작업을 수행한다. 각 MySQL 작업은 별도의 GitHub Runner에서 MySQL 8.0 서비스와 새 데이터베이스를 만든다. MySQL 비밀번호는 공개된 합성 테스트 값이며 기존 개발 DB와 Docker 볼륨을 사용하지 않는다. MinIO 자격증명은 해당 작업에서 무작위로 발급하고 로그 마스킹 후 임시 환경에만 전달한다.

| 작업 | 실제 검증 | 제한 |
| --- | --- | --- |
| `java21-build` | Java 21 Gradle 전체 빌드, 단위·HTTP·보안·Track 1/2·PDF 발급 차단 회귀 | 외부 MySQL·MinIO·FastAPI 조건부 테스트는 Skip |
| `mysql8-integration` | 팬 제보 마이그레이션 1건, JPA `ddl-auto=validate`와 MySQL 동시성·제약·정리 조회 5건, Track 1 동시 저장 1건 | 현 JPA 스키마에서 신규 변경분을 제거해 이전 구조를 모사한다. 모든 운영 마이그레이션을 빈 DB부터 재생하는 검증은 아니다. |
| `mysql8-track1-legacy` | 별도 DB에서 Track 1 고객사 범위 마이그레이션 1건 | 팬 제보 DB와 데이터를 공유하지 않음 |
| `minio-integration` | 별도 MySQL DB의 마이그레이션 1건·JPA 검증 5건, 실제 MinIO 저장소 3건과 MySQL 연계 복구 3건 | 소스 빌드 시간이 추가되며 GitHub Runner 결과는 새 커밋 Push 후 확인 필요 |

`check_junit_results.py`는 각 Gradle 실행의 JUnit XML에서 필수 테스트의 정확한 수, 실패 0건, Skip 0건을 확인한다. 기본 빌드에서는 예상 Skip 18건의 테스트 이름과 이유를 출력하고 클래스별 Skip 수가 다르면 작업을 실패시킨다. MySQL 조건부 8건은 MySQL 작업에서, MinIO 조건부 6건은 MinIO 작업에서 실행한다. MinIO의 테스트 환경변수는 기존 JUnit 정규식과 동일한 `http://127.0.0.1:19000`으로 설정한다. FastAPI 실서버 연동 2건, PDF 시각 검사 1건, 비활성화된 전체 컨텍스트 smoke 1건은 현재 CI에서 실행하지 않는다.

## MinIO 통합 환경

`S3EvidenceObjectStorageMinioTest`는 JPEG·PNG 원본 바이트와 SHA-256, 익명 GET·목록 거부, 잘못된 자격증명, 최소 권한, HeadBucket, 조건부 PUT 덮어쓰기 거부를 확인한다. `FanReportMinioMySqlTest`는 실제 객체와 MySQL을 사용해 중복 재시도, 업로드 실패 후 정리, DB 확정 실패 후 객체 정리, 완료 증거 보존을 확인한다. 각 테스트 클래스 3건이 모두 실행되지 않거나 하나라도 Skip되면 CI가 실패한다.

[MinIO Community Edition](https://github.com/minio/minio/blob/master/README.md)은 현재 소스 배포 방식이며 AGPLv3 적용 여부를 운영 도입 전에 검토해야 한다. 공식 `minio/minio:RELEASE.2025-04-22T22-12-26Z` 태그는 로컬 레지스트리 조회에서 접근이 거부됐다. 따라서 [고정 소스 빌드](../../.github/docker/minio-ci.Dockerfile)는 MinIO 서버 커밋 `7aac2a2c5b7c`, MinIO Client 릴리스 `RELEASE.2025-08-13T08-35-41Z`, Go 및 Alpine 이미지 digest를 고정한다. 빌드 시간과 네트워크 의존성이 있으며, 고정 버전은 유지보수 과정에서 갱신해야 한다. [초기화 스크립트](../../.github/scripts/start_minio_ci.sh)는 이번 작업 전용 컨테이너, 비공개 버킷, `fan-reports/*` 쓰기·삭제 및 버킷 확인 권한만 가진 임시 사용자를 생성한다. 컨테이너는 작업 종료 시 제거되며 공유 볼륨을 만들지 않는다.

로컬 actionlint와 격리 DB·MinIO 테스트는 GitHub 원격 Runner 통과를 대신하지 않는다. 기존 PR HEAD `43950b6`의 GitHub Actions 실행은 기본 빌드 159통과·18 Skip, MySQL 작업 8통과·0 Skip을 기록했다. 새 `minio-integration` 작업은 해당 원격 실행에 포함되지 않았으므로 Push 후 GitHub Actions 실행과 필수 테스트 6건의 0 Skip 결과를 확인해야 한다. 외부 공개 전에는 서버 측 암호화, 버전·변경 방지 정책, 보유기간 만료 후 실제 삭제, 프록시 업로드 제한과 분산 요청 제한, 개인정보 안내 및 운영 승인 절차를 별도로 확인해야 한다.
