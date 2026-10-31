# 관리자 팬 제보 조회 API 연동 안내 (Issue #54)

계약 원문은 [admin-fan-report-read-v1.yaml](admin-fan-report-read-v1.yaml)이다. 이 문서는 Frontend 관리자 Track 2 화면 연결에 필요한 요약이다.

## 엔드포인트

| 용도 | 요청 | 필수 값 |
| --- | --- | --- |
| 목록 | `GET /api/v1/admin/fan-reports` | `eventId`(UUID). 선택 `intakeStatus`, `page`(기본 0), `pageSize`(기본 20, 최대 100) |
| 상세 | `GET /api/v1/admin/fan-reports/{fanReportId}` | 경로 `fanReportId`(UUID) |
| 분석 이력 | `GET /api/v1/admin/fan-reports/{fanReportId}/evidence/{evidenceId}/listing-analyses` | 상세 응답의 `fanReportId`와 `evidence[].evidenceId` |

- 인증: `Authorization: Bearer <관리자 JWT>`. tenant, 역할 이름, 멤버십은 요청으로 받지 않고 서버가 JWT subject와 저장된 공연 소속으로 판단한다.
- 역할: ANALYST 또는 REVIEWER. 기존 분석 이력 API와 같다.
- 정렬: `receivedAt` 내림차순, 같은 시각이면 `fanReportId` 내림차순.
- 모든 성공 응답은 `Cache-Control: no-store`.

## 오류 정책

| 상태 | 의미 | code |
| --- | --- | --- |
| 400 | eventId 누락, UUID 형식 오류, page 또는 pageSize 범위 오류, 알 수 없는 intakeStatus | `INVALID_REQUEST` |
| 401 | JWT 없음, 서명 또는 issuer 또는 audience 또는 만료 검증 실패 | 본문 없음 (`WWW-Authenticate`) |
| 403 | 같은 고객사 공연 권한은 있으나 ANALYST 또는 REVIEWER 역할 없음 | `FORBIDDEN` |
| 404 | 없는 공연이나 제보, 다른 고객사 자원, 권한 없는 공연 자원 (존재 여부 비노출) | `RESOURCE_NOT_FOUND` |
| 503 | 데이터베이스 조회 실패. 빈 목록으로 대체하지 않음 | `DEPENDENCY_UNAVAILABLE` |

## 화면 표시 기준

- `intakeStatus`는 저장된 접수 상태이며 분석 결과나 부정 거래 판정이 아니다. 현재 접수 흐름이 기록하는 값은 `PENDING_STORAGE`, `RECEIVED`, `STORAGE_FAILED`이다.
- Frontend 데모 모델의 `reviewStatus`, `extractionId`, `comparisonId`에 해당하는 서버 저장값은 없다. 추출 결과는 분석 이력 API로 증거별로 조회한다.
- `evidence[].originalStored`가 false인 증거는 정상 원본으로 표시하지 않는다. 이때 `sha256`은 null이다.
- `listingUrl`은 팬 입력 문자열이다. 자동 요청, 링크 미리보기, iframe 임베드를 하지 않는다.
- 원본 이미지 다운로드와 미리보기는 아직 제공하지 않는다. 객체 키나 임의 URL로 이미지를 요청하지 않는다.
