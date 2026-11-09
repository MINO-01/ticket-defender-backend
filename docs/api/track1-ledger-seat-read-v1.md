# Track 1 군집 구성원 합성 원장 좌석 조회 v1

## API

`GET /api/v1/admin/track1/clusters/detail`은 기존 JWT, tenant/event, 역할 검증을 그대로 사용한다.
기존 `eventId`, `analysisRequestId`, `clusterId`에 아래 두 query parameter를 **함께** 제공하면 저장된 군집 구성원별로 현재 합성 원장을 조회한다.

```
performanceId=<server-verified-performance-uuid>
sourceVersion=<fixed-synthetic-ledger-source-version>
```

둘 다 생략하면 기존 군집 상세 조회와 같으며 `members[].ledgerSeat`은 `null`이다. 하나만 제공하거나 공백/128자 초과 `sourceVersion`을 제공하면 `400`이다. 다른 tenant/event의 회차, 철회된 회차는 존재를 감추기 위해 `404`이다.

## 응답

```json
{
  "members": [
    {"auditId": 123, "ledgerSeat": {"status": "FOUND", "zone": "A", "row": "1", "seat": "2"}},
    {"auditId": 124, "ledgerSeat": {"status": "NOT_IN_LEDGER", "zone": null, "row": null, "seat": null}}
  ]
}
```

`ledgerSeat.status`는 다음 중 하나다.

- `FOUND`: server-verified tenant/event/회차의 현재 `ACCEPTED`, `completeSnapshot=true` 원장에서 해당 구성원의 좌석을 찾았다.
- `NOT_IN_LEDGER`: 완전한 현재 원장 조회는 성공했지만 해당 구성원의 기존 예매참조 HMAC이 없다.
- `LEDGER_UNAVAILABLE`: 현재 원장, sourceVersion, batch, HMAC key version 또는 조회 의존성이 검증되지 않았다. 빈 성공 결과로 대체하지 않는다.

Backend는 기존 `TenantPurposeReferenceHasher`로 구성원 예매참조를 tenant 범위 HMAC으로 변환한 뒤 원장을 조회한다. 계정 원문, 예매참조 원문, HMAC 값, object key는 응답·로그에 포함하지 않는다. `FOUND`는 티켓 진위, 판매자 소유권, 부정행위, Case 승인 또는 PDF 발급의 증명이 아니다.

## Case 수렴 API와의 차이

`GET /api/v1/cases/{caseId}/track1-candidate-links`는 이미 생성된 Case의 `graphClusters`와 `candidateRunIds`를 함께 읽는다. Track 2 `MATCH_CANDIDATE` 실행의 고정 batch/sourceVersion/회차와 비교해 `LINKED`, `NO_TRACK1_LINK`, `NOT_APPLICABLE`, `LEDGER_UNAVAILABLE`를 반환한다. `NO_TRACK1_LINK`는 고정 후보를 실제로 확인해 구성원과 비교한 결과에만 사용하며, 후보 레코드가 없거나 후보의 batch가 실행 이력과 다르면 `LEDGER_UNAVAILABLE`이다.

반면 군집 상세의 `ledgerSeat`은 Case 생성 전 조사 화면에서 구성원별 원장 좌석 존재만 보여 준다. Track 2 후보 실행이나 Case를 생성·변경하지 않으며 `LINKED` 판정을 만들지 않는다.

## Frontend 연결 순서

1. 기존 공연 범위에서 군집 목록과 상세를 조회한다.
2. 관리자가 server-verified 회차와 고정 `sourceVersion`을 선택한 뒤 동일 상세 API를 다시 조회해 `ledgerSeat` 상태를 표시한다.
3. 팬 제보·증거·실제 Track 2 분석과 후보 대조가 끝난 후에만, 필요하면 기존 `graphClusters`와 `candidateRunIds`로 Case를 생성한다.
4. 생성된 Case에는 Case 수렴 API를 별도로 호출한다. `NO_TRACK1_LINK`도 실제 결과로 표시하며 `LINKED`로 보정하지 않는다.

저장된 관계 근거를 넘는 그래프 간선 API는 제공하지 않는다. 기존 상세의 `relationship`은 저장된 device/IP 근거의 집계만 제공하며, 원문 신호나 추정 간선을 생성하지 않는다.
