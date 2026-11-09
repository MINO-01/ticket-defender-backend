# Track 1 관리자 저장 증거 조회 API

## 요청

관리자 JWT를 `Authorization: Bearer` 헤더로 전달한다. 두 경로 모두 `eventId`에 내부 공연 UUID를 필수로 받는다. 서버는 JWT subject의 활성 고객사 멤버십, 역할, 공연 권한을 확인한 뒤 고객사 UUID를 결정한다. 요청의 고객사 주장은 권한 근거로 사용하지 않는다.

```http
GET /api/v1/admin/track1/clusters?eventId=33333333-3333-4333-8333-333333333333&page=0&pageSize=20&sort=priority-desc
GET /api/v1/admin/track1/clusters/detail?eventId=33333333-3333-4333-8333-333333333333&analysisRequestId=7f3c2a10-5b4e-4d6f-9a81-2c3d4e5f6071&clusterId=cluster-1
GET /api/v1/admin/track1/clusters/detail?eventId=33333333-3333-4333-8333-333333333333&analysisRequestId=7f3c2a10-5b4e-4d6f-9a81-2c3d4e5f6071&clusterId=cluster-1&performanceId=<server-verified-performance-uuid>&sourceVersion=<fixed-synthetic-ledger-source-version>
```

상세 요청의 `performanceId`와 `sourceVersion`은 선택 파라미터이며 반드시 함께 제공한다. 두 값을 제공했을 때의 원장 좌석 조회 계약은 [Track 1 군집 구성원 합성 원장 좌석 조회 v1](track1-ledger-seat-read-v1.md)을 따른다.

목록은 `page`가 0부터 시작하고 `pageSize`는 1부터 100까지 허용한다. 기본값은 각각 0과 20이다. `sort`는 `priority-desc`(기본값), `priority-asc`, `recent` 중 하나다. 동점일 때 분석 요청 ID와 군집 ID로 순서를 고정한다.

## 목록 응답 예시

```json
{
  "event": {
    "id": "33333333-3333-4333-8333-333333333333",
    "externalEventId": "shared-event",
    "name": "공연 A"
  },
  "items": [
    {
      "analysisRequestId": "7f3c2a10-5b4e-4d6f-9a81-2c3d4e5f6071",
      "clusterId": "cluster-1",
      "riskScore": 0.82,
      "analysisAlgorithm": "LOUVAIN",
      "analysisAlgorithmVersion": "1.0.0",
      "analyzedAt": "2026-10-08T10:15:30Z",
      "storedMemberCount": 2
    }
  ],
  "page": 0,
  "pageSize": 20,
  "totalItems": 1,
  "hasNext": false,
  "coverage": "STORED_EVIDENCE_ONLY"
}
```

## 상세 응답 예시

```json
{
  "event": {
    "id": "33333333-3333-4333-8333-333333333333",
    "externalEventId": "shared-event",
    "name": "공연 A"
  },
  "cluster": {
    "analysisRequestId": "7f3c2a10-5b4e-4d6f-9a81-2c3d4e5f6071",
    "clusterId": "cluster-1",
    "riskScore": 0.82,
    "analysisAlgorithm": "LOUVAIN",
    "analysisAlgorithmVersion": "1.0.0",
    "analyzedAt": "2026-10-08T10:15:30Z",
    "storedMemberCount": 1
  },
  "members": [
    {"auditId": 123, "ledgerSeat": null}
  ],
  "coverage": "STORED_EVIDENCE_ONLY"
}
```

`analysisRequestId`와 `clusterId`는 문자열이며, 고객사와 내부 공연을 포함한 범위 안에서 함께 군집을 식별한다. 상세 요청에서는 두 값을 쿼리 파라미터로 전달한다. `members`의 각 항목은 기존 감사 행 식별자 `auditId`와 `ledgerSeat`을 포함하며 계정 식별자, 예매참조, HMAC은 반환하지 않는다. 원장 조회 조건(`performanceId`, `sourceVersion`)이 없으면 위 예시처럼 `ledgerSeat`은 `null`이다. 조건을 제공하면 `ledgerSeat.status`는 `FOUND`, `NOT_IN_LEDGER`, `LEDGER_UNAVAILABLE` 중 하나이고, 각 상태의 의미는 [원장 좌석 조회 문서](track1-ledger-seat-read-v1.md)에 정의한다.

## 식별자 형식

- `analysisRequestId`: 정상 분석 경로에서는 Spring이 분석 요청마다 `UUID.randomUUID().toString()`으로 만든 표준 소문자 UUID(예: `7f3c2a10-5b4e-4d6f-9a81-2c3d4e5f6071`)다. FastAPI는 요청의 `requestId`를 바꾸지 않고 돌려주며, Spring은 요청과 응답의 ID가 다르면 결과를 저장하지 않는다. 저장된 분석 증거, 이 조회 API, Case 출처는 모두 같은 값을 사용한다.
- `clusterId`: 분석 결과의 군집 식별자로 UUID가 아닌 별도 문자열이다(예: `cluster-1`). 같은 `clusterId`가 다른 분석 요청에서 다시 나올 수 있으므로 항상 `analysisRequestId`와 함께 사용한다. 슬래시를 포함할 수 있으므로 경로 대신 쿼리 파라미터로 전달한다.

이 조회 API는 저장된 `analysisRequestId`의 형식을 따로 검사하지 않는다. 과거 합성 데이터나 테스트 데이터처럼 `run-1` 같은 비UUID 값으로 저장된 행이 있으면 목록과 상세에서 그대로 조회될 수 있다.

## Case 생성과의 연결

`POST /api/v1/cases`의 `graphClusters[].analysisRequestId`는 표준 소문자 UUID여야 한다. 비UUID 값과 대문자 UUID는 저장된 행이 있더라도 `422 VALIDATION_FAILED`로 거부한다. 따라서 위의 비UUID 합성 행은 조회는 되지만 Case 출처로 연결할 수 없다. 형식이 맞더라도 요청자의 고객사와 공연 범위에 저장된 군집이 없으면 `404 RESOURCE_NOT_FOUND`로 처리하며, 다른 고객사나 다른 공연의 군집 존재 여부를 노출하지 않는다. 거부된 요청은 Case, 출처, 멱등성 기록을 남기지 않는다. 정상 분석 결과의 ID는 이 조회 API가 돌려준 값을 바꾸지 않고 그대로 Case 생성에 사용하면 된다. `auditId`는 기존 `TicketAudit.id`의 `Long` 값이다. `analyzedAt`은 저장된 UTC 분석 시각을 ISO 8601로 직렬화한다.

`storedMemberCount`와 `members`는 실제 저장된 증거만 나타낸다. 동일 예매의 중복 저장 방지로 분석 응답의 일부 구성원이 저장되지 않을 수 있으므로 전체 분석 스냅샷이나 확정 판정으로 해석하지 않는다. `riskScore`는 0부터 1 사이의 조사 우선순위 신호이며 부정판매 확률이 아니다. 고객사 미귀속 증거는 조회하지 않는다.

Frontend FE-03은 공연 이름, 분석 메타데이터, 저장 구성원 식별자를 사용할 수 있다. 저장된 군집 증거에는 좌석과 그래프 간선이 없다. 좌석은 원장 조회 조건을 제공한 상세 요청에서만 `ledgerSeat`으로 반환하며, 간선은 반환하지 않는다. 상세의 `relationship`은 저장된 기기와 IP 근거의 집계만 제공한다. 결제, 주소, 기기, IP 토큰 원문은 반환하지 않는다. 완전한 그래프 화면 연동에는 별도 영속 구조와 검증이 필요하다.

## 오류

- 400: 내부 공연 UUID, 페이지 범위 또는 정렬 값이 잘못됨. 상세의 `performanceId`와 `sourceVersion` 중 하나만 제공했거나, `performanceId`가 UUID가 아니거나, `sourceVersion`이 공백이거나 128자를 초과함
- 401: 관리자 JWT가 없거나 유효하지 않음. 반입 서비스 JWT도 거부됨
- 403: 활성 멤버십은 있으나 허용된 역할이 없음
- 404: 공연 권한이 없거나 요청한 군집이 해당 범위에 없음. `performanceId`가 다른 tenant/event의 회차이거나 철회되어 활성 상태가 아님
- 500: 군집 증거 DB 조회 실패 또는 저장된 군집의 분석 출처 정보가 서로 다름. 빈 목록으로 대체하지 않음. 원장 조회 실패는 500이 아니라 구성원별 `ledgerSeat.status=LEDGER_UNAVAILABLE`로 반환함

Case 및 ApprovalRequest 경로와 충돌하지 않는다. 공식 PDF 발급 경로는 활성화하지 않는다.
