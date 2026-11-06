# Track 1 내부 인증 배포 절차

Spring의 `FastApiAdapter`는 `POST /api/v1/clusters/analyze`에만 `X-TD-Track1-Key`를 한 번 전송한다. Track 2 VLM의 `X-TD-Internal-Key` 및 `VLM_INTERNAL_SHARED_SECRET`과는 별도의 인증 경계다.

## 설정

- Spring과 Data의 안전한 런타임 비밀 주입 경로에 같은 이름의 `TRACK1_INTERNAL_SHARED_SECRET`를 설정한다.
- 값은 UTF-8로 32바이트 이상이어야 하며 `VLM_INTERNAL_SHARED_SECRET`와 달라야 한다.
- HTTP 헤더 규칙상 제어문자를 포함할 수 없다.
- 값은 저장소, 예제 환경 파일, 로그, 오류 응답 또는 배포 문서에 기록하지 않는다.
- Spring은 값이 없거나 짧으면 FastAPI HTTP 호출 전에 분석을 차단하고 분석 불가로 처리한다.
- 원격 FastAPI 연결은 HTTPS만 허용하며 기존 TLS 인증서·호스트명 검증을 그대로 사용한다.
- 평문 HTTP는 검증된 loopback 연결에만 허용한다. `localhost`는 모든 해석 결과가 loopback일 때 수치 loopback 주소로 고정하고, 명시적 loopback IP 외 사설 IP·Docker 서비스명·유사 호스트는 허용하지 않는다.
- URL의 사용자정보·질의문자열·fragment·지원하지 않는 스킴은 차단한다. 요청별 redirect도 자동으로 따르지 않아 인증 헤더를 새 주소로 재전송하지 않는다.

## 배포 전 연결 확인

1. Spring 배포 전에 `FASTAPI_URL`이 HTTPS 또는 검증된 loopback HTTP 규칙에 맞는지 확인한다. 기존 원격 HTTP 주소는 먼저 정상 동작하는 HTTPS 엔드포인트로 전환한다.
2. 평문 HTTP는 검증된 loopback 주소에만 허용된다. 원격 HTTP, 사설망 주소 또는 Docker 서비스명의 평문 HTTP를 임의로 허용하거나 TLS 검증 우회 설정을 추가하지 않는다.
3. Track 1 전용 인증키를 두 서비스에 주입하고 배포하기 전에, 격리 환경에서 연결 가능성과 TLS 인증서·호스트명 검증이 성공하는지 확인한다.
4. URL 또는 인증 설정의 사전 검증에 실패하면 Track 1 분석은 `UNAVAILABLE`이 될 수 있다. 이 상태를 정상 완료나 군집 0건으로 처리하지 않는다.

## 격리 live 통합 실행기

- `run_track1_live_integration.ps1`과 `run_track1_case_link_live_mysql.ps1`은 테스트 전용 합성 인증값을 주입한 새 격리 PowerShell Process에서만 실행한다.
- 운영 서비스 프로세스나 운영 인증값을 상속한 셸에서 실행하지 않는다. 두 실행기는 인증값을 생성·저장·대체하지 않으며 호출자가 제공한 Process 범위 값만 검증한다.
- 검증된 동일한 테스트 전용 값은 FastAPI와 Gradle 테스트 프로세스가 Process 환경으로 상속한다. 인증값을 명령줄 인수, 로그, 콘솔 또는 테스트 결과에 기록하지 않는다.

## 공동 배포와 검증

1. 두 서비스의 비밀 저장소에 Track 1 전용 값을 준비하고, Track 2 값과 분리됐는지 운영 절차에서 확인한다.
2. Spring을 먼저 배포한다. 이전 Data 버전은 추가 헤더를 무시하므로 호환된다.
3. Data의 Track 1 인증 변경을 배포한다.
4. 격리 환경의 합성 티켓으로 정상 키의 200 응답과 잘못된 키의 401 응답을 확인한다. 401, 503, 410은 완료 또는 빈 군집으로 취급하지 않는다.
5. Spring과 Data 로그 및 오류 응답에 인증값이나 헤더 값이 없는지 점검한다.

Data가 인증을 적용한 뒤에는 Spring을 인증 헤더 없는 이전 버전으로 단독 롤백하지 않는다. 양쪽의 런타임 설정과 배포 상태를 함께 되돌려야 한다.
