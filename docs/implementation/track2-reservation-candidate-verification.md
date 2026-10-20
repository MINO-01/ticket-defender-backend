# Track 2 예매 후보 대조 규칙

이 문서는 이슈 #30의 현재 Spring 내부 경로를 설명한다. `VlmAnalysisService`를 호출하는 HTTP 엔드포인트나 제보·추출 영속 모델은 아직 없다. 기존 `/api/v1/audits/analyze`와 `FastApiAdapter`의 Track 1 계약은 바꾸지 않았다.

## 입력과 조회 범위

- `VlmParsedData`의 구역·열·좌석·`mappingScore`는 이미지 추출 결과다. `mappingScore`는 추출 신뢰도이며 예매 일치 신뢰도나 부정판매 확률이 아니다. `ExtractionStatus.FAILED`는 파싱 장애이고, 낮은 점수나 읽지 못한 필드는 `UNVERIFIABLE`이다.
- `ReportScope(tenantId,eventId,performanceRef)`는 인증·인가된 제보의 **서버 저장 범위**에서 얻어야 한다. VLM 출력, 요청 본문에 적힌 임의의 고객사 ID, 캡처에 보이는 공연 이름으로 구성하지 않는다. `performanceRef`는 예매처가 공연 안에서 회차에 부여한 안정적인 식별자다. 일시 또는 회차 번호를 추정해 채우지 않는다.
- 서비스는 세 범위 값과 읽을 수 있는 좌석 정보가 모두 있을 때만 활성 고객사·공연 관계를 확인한다. 고객사와 공연 소속이 다르거나 회차가 없으면 예매 조회를 하지 않는다. 조회 조건은 `tenant_id, event_id, performance_ref, zone, row_num, seat_num`의 정확한 일치다. 같은 범위에서 후보가 두 건 이상이면 임의로 한 계정을 택하지 않는다.
- 종전 단일 인자 `processVlmReport`는 호출 호환성을 위해 남겨 두되 범위가 없으므로 조회와 저장을 하지 않는다. 후속 제보 API는 저장된 제보의 범위와 인증된 호출자 권한을 확인한 뒤 두 인자 메서드를 호출해야 한다. 현재 이 연결이 없으므로 운영 제보가 자동으로 후보 조회에 들어간다고 간주하지 않는다.

## 결과 상태

| 서비스 결과 | 의미 | 감사 저장 |
|---|---|---|
| `MATCH_CANDIDATE` | 범위 안의 예매 후보가 정확히 한 건 있음 | `FAN_REPORT`, `MATCH_CANDIDATE` 저장. `detectedAt` 미설정 |
| `NO_MATCH` | 범위 조회 성공, 후보 0건 | 없음 |
| `UNVERIFIABLE` | 좌석·공연·회차 범위가 없거나 후보가 여러 건임 | 없음 |
| `LOOKUP_FAILED` | 공연 범위 또는 예매 저장소 조회 장애 | 없음 |
| `EXTRACTION_FAILED` | VLM 파싱 실패 | 없음 |

`TicketAudit.mappingScore`는 원본 추출 신뢰도이고, 저장한 `reservationNo/accountId`는 예매 후보의 DB 값이다. 둘을 동일한 확인값으로 취급하지 않는다. 후보가 있어도 판매자 신원이나 부정판매가 확정되지 않으며 `FRAUD_DETECTED`로 전이하지 않는다. 기존 Track 1 `MACRO_GRAPH` 저장 방식과 상태는 유지한다. 공식 PDF 발급 서비스의 차단은 계속 적용되고 후보 상태는 PDF 렌더링도 거부한다. 운영 로그에는 생성한 요청 ID와 결과 상태만 남긴다.

## MySQL 적용과 후속 계약

`docs/database/track2-reservation-scope-migration.sql`을 기존 관리자 공연 테이블 적용 후 MySQL 8.0.16 이상에서 실행한다. `reservation`에 nullable `tenant_id`, `event_id`, `performance_ref`와 범위 인덱스·공연 소속 FK·부분 범위 차단 CHECK를 추가한다. 회차와 좌석 컬럼에는 이진 대조 정렬 규칙을 적용해 MySQL 기본 대소문자 무시 정렬 규칙으로 다른 회차·좌석이 섞이지 않게 한다. `ticket_audit.status`의 기존 ENUM 값은 유지하면서 `MATCH_CANDIDATE`를 추가한다. 기존 예매 행은 출처가 확인되지 않아 범위가 모두 null로 남고 후보 조회에서 제외된다. `ddl-auto: validate`이므로 마이그레이션 적용 전 새 앱을 운영 DB에 배포할 수 없다.

후속 제보·예매 반입 계약은 인증된 고객사와 저장된 공연 ID, 예매처의 회차 식별자, 제보 권한을 함께 제공해야 한다. 제보 모델에 이 범위가 없으면 `UNVERIFIABLE`로 남긴다. 현재 `reservation_no` 전역 유일 키와 `TicketAudit`의 범위 컬럼 부재는 유지하므로 고객사별 동일 예매 번호를 지원하거나 Track 2 감사를 고객사별로 읽는 API를 만들 때 별도 이행·권한 설계가 필요하다. 기존 행을 공연에 소급 연결하거나 회차를 생성하지 않는다.

## 격리된 MySQL 검증 기록

2026-10-20에 MySQL 8.0.45의 폐기 가능한 로컬 인스턴스(`127.0.0.1:13306`)에서 확인했다. `origin/main`의 엔티티에 해당하는 예매 범위 없는 스키마와 기존 감사 상태 ENUM을 재현하고 예매 행을 넣은 뒤 마이그레이션을 실행했다. 운영 스키마와 운영 데이터는 직접 검증하지 않았다.

- 최초 SQL에서는 `MATCH_CANDIDATE` 저장이 MySQL 오류 1265로 실패했고, `Round-A` 조회에 `round-a` 예매까지 포함됐다.
- 보정 SQL 적용 후 기존 예매 1건은 보존되고 세 범위 컬럼은 모두 null이었다. 신규 상태 저장에 성공했고 기존 `FRAUD_DETECTED` 행 6건도 유지됐다.
- 같은 좌석의 다른 고객사·공연·회차·좌석 표기는 조회에서 제외됐다. 동일 범위의 중복 좌석 2건은 두 후보로 반환됐다. 잘못된 고객사·공연 조합은 FK가, 부분 범위와 빈 회차는 CHECK가 거부했다.
- 범위 인덱스와 FK·CHECK를 확인했고, 기존 `Track1ConcurrentMySqlTest`의 `ddl-auto=validate` 및 Track 1 동시성 검증이 통과했다.
