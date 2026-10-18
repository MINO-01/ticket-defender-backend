# ticket-defender-backend

## 인증 설정

Spring은 외부 발급자의 RS256 JWT를 검증합니다. 관리자와 Track 1 반입 호출자는 각각 다음 설정을 사용합니다. JWK 주소와 issuer는 HTTPS여야 합니다.

| 호출자 | 필수 설정 |
|---|---|
| 관리자 | `ticket-defender.security.admin.issuer`, `ticket-defender.security.admin.jwk-set-uri`, `ticket-defender.security.admin.audience` |
| Track 1 반입 서비스 | `ticket-defender.security.analysis.issuer`, `ticket-defender.security.analysis.jwk-set-uri`, `ticket-defender.security.analysis.audience`, `ticket-defender.security.analysis.client-id` |

설정이 없으면 해당 호출자의 토큰을 모두 거부합니다. 일부만 설정하면 서버가 시작되지 않습니다. 서명, issuer, audience, 만료 시각, subject를 검증하며 반입 서비스에는 `client_id`도 요구합니다. 두 audience는 별도로 발급하고 관리해야 합니다. 로그인 또는 JWT 발급 서버는 이 저장소에 없습니다.

현재 `/api/v1/audits/analyze`는 반입 서비스 토큰만 받습니다. 기존 공식 PDF 경로는 관리자 토큰이 있어도 승인 절차 전까지 409를 반환합니다. 그 외 경로는 기본 거부입니다. `AdminAccessService`는 인증 subject와 서버 저장 멤버십·공연 소속을 대조하도록 구현했지만, 영속 멤버십과 공연 저장소가 없어 운영 기본 조회 구현은 접근을 거부합니다. 후속 관리자 API는 객체의 저장된 tenant/event 범위로 이 서비스를 호출해야 합니다.

Spring에서 FastAPI로 나가는 기존 `FastApiAdapter`는 별도 서비스 인증을 아직 전달하지 않습니다. FastAPI 측 인증 계약과 발급자가 확정되기 전에는 이 내부 연결을 운영용 다중 고객사 경계로 간주하지 않습니다.
