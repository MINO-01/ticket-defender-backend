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

## 공동 배포와 검증

1. 두 서비스의 비밀 저장소에 Track 1 전용 값을 준비하고, Track 2 값과 분리됐는지 운영 절차에서 확인한다.
2. Spring을 먼저 배포한다. 이전 Data 버전은 추가 헤더를 무시하므로 호환된다.
3. Data의 Track 1 인증 변경을 배포한다.
4. 격리 환경의 합성 티켓으로 정상 키의 200 응답과 잘못된 키의 401 응답을 확인한다. 401, 503, 410은 완료 또는 빈 군집으로 취급하지 않는다.
5. Spring과 Data 로그 및 오류 응답에 인증값이나 헤더 값이 없는지 점검한다.

Data가 인증을 적용한 뒤에는 Spring을 인증 헤더 없는 이전 버전으로 단독 롤백하지 않는다. 양쪽의 런타임 설정과 배포 상태를 함께 되돌려야 한다.
