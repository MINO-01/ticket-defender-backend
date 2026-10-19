# Track 1 관리자 저장 증거 조회 API

## 요청

관리자 JWT를 `Authorization: Bearer` 헤더로 전달한다. 두 경로 모두 `eventId`에 내부 공연 UUID를 필수로 받는다. 서버는 JWT subject의 활성 고객사 멤버십, 역할, 공연 권한을 확인한 뒤 고객사 UUID를 결정한다. 요청의 고객사 주장은 권한 근거로 사용하지 않는다.

```http
GET /api/v1/admin/track1/clusters?eventId=33333333-3333-4333-8333-333333333333&page=0&pageSize=20&sort=priority-desc
GET /api/v1/admin/track1/clusters/detail?eventId=33333333-3333-4333-8333-333333333333&analysisRequestId=run-1&clusterId=cluster-1
```

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
      "analysisRequestId": "run-1",
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
    "analysisRequestId": "run-1",
    "clusterId": "cluster-1",
    "riskScore": 0.82,
    "analysisAlgorithm": "LOUVAIN",
    "analysisAlgorithmVersion": "1.0.0",
    "analyzedAt": "2026-10-08T10:15:30Z",
    "storedMemberCount": 1
  },
  "members": [
    {"auditId": 123, "accountId": "account-1", "reservationNo": "reservation-1"}
  ],
  "coverage": "STORED_EVIDENCE_ONLY"
}
```

`analysisRequestId`와 `clusterId`는 문자열이며, 고객사와 내부 공연을 포함한 범위 안에서 함께 군집을 식별한다. 두 ID는 슬래시를 포함할 수 있어 상세 요청에서 쿼리 파라미터로 전달한다. `auditId`는 기존 `TicketAudit.id`의 `Long` 값이다. `analyzedAt`은 저장된 UTC 분석 시각을 ISO 8601로 직렬화한다.

`storedMemberCount`와 `members`는 실제 저장된 증거만 나타낸다. 동일 예매의 중복 저장 방지로 분석 응답의 일부 구성원이 저장되지 않을 수 있으므로 전체 분석 스냅샷이나 확정 판정으로 해석하지 않는다. `riskScore`는 0부터 1 사이의 조사 우선순위 신호이며 부정판매 확률이 아니다. 고객사 미귀속 증거는 조회하지 않는다.

Frontend FE-03은 공연 이름, 분석 메타데이터, 저장 구성원 식별자를 사용할 수 있다. 현재 저장 구조에는 좌석, 공유 신호 설명, 신호별 연결 관계, 그래프 간선이 없으므로 이 API는 해당 데이터를 반환하지 않는다. 결제, 주소, 기기, IP 토큰도 반환하지 않는다. 완전한 그래프 화면 연동에는 별도 영속 구조와 검증이 필요하다.

## 오류

- 400: 내부 공연 UUID, 페이지 범위 또는 정렬 값이 잘못됨
- 401: 관리자 JWT가 없거나 유효하지 않음. 반입 서비스 JWT도 거부됨
- 403: 활성 멤버십은 있으나 허용된 역할이 없음
- 404: 공연 권한이 없거나 요청한 군집이 해당 범위에 없음
- 500: DB 조회 실패 또는 저장된 군집의 분석 출처 정보가 서로 다름. 빈 목록으로 대체하지 않음

Case 및 ApprovalRequest 경로와 충돌하지 않는다. 공식 PDF 발급 경로는 활성화하지 않는다.
