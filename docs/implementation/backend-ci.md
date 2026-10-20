# Backend PR CI 검증 범위

[Backend CI 워크플로](../../.github/workflows/backend-ci.yml)는 PR, main Push, 수동 실행에서 다음 세 작업을 수행한다. 각 MySQL 작업은 별도의 GitHub Runner에서 MySQL 8.0 서비스와 새 데이터베이스를 만든다. 자격증명은 합성 테스트 값이며 기존 개발 DB와 Docker 볼륨을 사용하지 않는다.

| 작업 | 실제 검증 | 제한 |
| --- | --- | --- |
| `java21-build` | Java 21 Gradle 전체 빌드, 단위·HTTP·보안·Track 1/2·PDF 발급 차단 회귀 | 외부 MySQL·MinIO·FastAPI 조건부 테스트는 Skip |
| `mysql8-integration` | 팬 제보 마이그레이션 1건, JPA `ddl-auto=validate`와 MySQL 동시성·제약·정리 조회 5건, Track 1 동시 저장 1건 | 현 JPA 스키마에서 신규 변경분을 제거해 이전 구조를 모사한다. 모든 운영 마이그레이션을 빈 DB부터 재생하는 검증은 아니다. |
| `mysql8-track1-legacy` | 별도 DB에서 Track 1 고객사 범위 마이그레이션 1건 | 팬 제보 DB와 데이터를 공유하지 않음 |

`check_junit_results.py`는 각 Gradle 실행의 JUnit XML에서 필수 테스트의 정확한 수, 실패 0건, Skip 0건을 확인한다. 기본 빌드에서는 예상 Skip 18건의 테스트 이름과 이유를 출력하고 클래스별 Skip 수가 다르면 작업을 실패시킨다. MySQL 조건부 8건은 위 통합 작업에서 실행한다. MinIO 조건부 6건, FastAPI 실서버 연동 2건, PDF 시각 검사 1건, 비활성화된 전체 컨텍스트 smoke 1건은 현재 CI에서 실행하지 않는다.

## MinIO 자동화 보류

기존 `S3EvidenceObjectStorageMinioTest` 3건과 `FanReportMinioMySqlTest` 3건은 이전 로컬 격리 MinIO·MySQL 검증에서 통과했다. 이번 CI 작업 중 Docker daemon 연결이 끊겨 새로운 MinIO 시작 절차와 CI 작업을 실제 실행할 수 없었다. 실행되지 않은 MinIO 워크플로는 커밋하지 않았다. Docker가 복구되면 작업 전용 MinIO 인스턴스와 새 MySQL DB에서 두 테스트 클래스를 다시 실행하고, 객체 바이트·SHA-256, 익명 GET·목록 거부, 최소 권한, 조건부 PUT, 저장 실패 후 정리와 완료 증거 보존을 검증한다.

[MinIO Community Edition](https://github.com/minio/minio/blob/master/README.md)은 현재 소스 배포 방식이다. 역사적 공식 컨테이너 이미지를 고정하면 다운로드 가능성과 갱신 상태가 불확실하다. 소스 버전과 Go 빌더를 고정한 이미지 빌드는 재현 가능하지만 빌드 시간·메모리와 버킷·IAM 초기화 검증이 추가로 필요하다. 별도 통합 워크플로는 기본 CI의 빠른 실패 신호를 유지할 수 있으나, 실제 실행 없이 성공 조건으로 등록할 수 없다. Docker가 복구된 뒤 소스 빌드와 임시 최소 권한 계정을 검증하고 분리된 MinIO 통합 작업을 추가한다.

로컬 actionlint와 격리 DB 테스트는 GitHub 원격 Runner 통과를 대신하지 않는다. PR 병합 전에는 GitHub Actions 실행 결과와 Skip 집계를 확인한다. 외부 공개 전에는 서버 측 암호화, 버전·변경 방지 정책, 보유기간 만료 후 실제 삭제, 프록시 업로드 제한과 분산 요청 제한, 개인정보 안내 및 운영 승인 절차를 별도로 확인해야 한다.
