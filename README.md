# ticket-defender-backend

## 인증 설정

Spring은 외부 발급자의 RS256 JWT를 검증합니다. 관리자와 Track 1 반입 호출자는 각각 다음 설정을 사용합니다. JWK 주소와 issuer는 HTTPS여야 합니다.

| 호출자 | 필수 설정 |
|---|---|
| 관리자 | `ticket-defender.security.admin.issuer`, `ticket-defender.security.admin.jwk-set-uri`, `ticket-defender.security.admin.audience` |
| Track 1 반입 서비스 | `ticket-defender.security.analysis.issuer`, `ticket-defender.security.analysis.jwk-set-uri`, `ticket-defender.security.analysis.audience`, `ticket-defender.security.analysis.client-id` |

설정이 없으면 해당 호출자의 토큰을 모두 거부합니다. 일부만 설정하면 서버가 시작되지 않습니다. 서명, issuer, audience, 만료 시각, subject를 검증하며 반입 서비스에는 `client_id`도 요구합니다. 두 audience는 별도로 발급하고 관리해야 합니다. 로그인 또는 JWT 발급 서버는 이 저장소에 없습니다.

현재 `/api/v1/audits/analyze`는 반입 서비스 토큰만 받습니다. 기존 공식 PDF 경로는 관리자 토큰이 있어도 승인 절차 전까지 409를 반환합니다. 그 외 경로는 기본 거부입니다. `AdminAccessService`는 인증 subject와 MySQL의 활성 멤버십·공연 소속을 대조합니다. 후속 관리자 API는 객체의 저장된 tenant/event 범위로 이 서비스를 호출해야 합니다.

## 관리자 권한 데이터베이스 적용

기존 MySQL 데이터를 백업하고 기존 Track 1 스키마를 확인한 다음 `docs/database/admin-membership-event-migration.sql`을 적용합니다. 기존 변경 이력이 명시적 SQL이므로 같은 방식을 사용했습니다. Flyway 도입에는 비어 있지 않은 기존 DB의 baseline 확정이 먼저 필요합니다. 이 스크립트는 새 권한 테이블만 만들며 기존 `reservation`, `ticket_audit`, `macro_audit_evidence`를 수정하거나 기존 분석 결과에 고객사를 부여하지 않습니다. 재실행 시 기존 테이블 생성은 건너뛰지만, 구조가 다른 동명 테이블을 고치지는 않으므로 배포 전 스키마를 확인해야 합니다. 애플리케이션은 `ddl-auto: validate`로 시작하며 자동으로 테이블을 만들지 않습니다.

내부 공연 UUID와 외부 공연 문자열 ID는 `events`의 고객사별 유일 키로 연결합니다. JWT subject는 `memberships.subject`와 정확히 일치해야 하며 역할은 `membership_roles`, 허용 공연은 `membership_events`에서 조회합니다. 비활성 고객사·멤버십·공연 및 조회 장애는 접근을 허용하지 않습니다. 실제 운영 인증 공급자와 멤버십 등록 절차는 별도 연결이 필요합니다.

기존 `Reservation.reservationNo`의 전역 유일성, `MacroAuditEvidence.eventId`의 문자열 형식, `MacroEvidenceKey`의 고객사 범위 부족은 그대로입니다. 현재 분석 결과를 신규 고객사나 공연에 자동 연결하지 않으며, 다중 고객사 분석 저장과 Case 조회는 아직 구현되지 않았습니다.

Spring에서 FastAPI로 나가는 기존 `FastApiAdapter`는 별도 서비스 인증을 아직 전달하지 않습니다. FastAPI 측 인증 계약과 발급자가 확정되기 전에는 이 내부 연결을 운영용 다중 고객사 경계로 간주하지 않습니다.
