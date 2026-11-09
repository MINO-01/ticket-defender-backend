# Track 1 군집별 좌석 조사 조회 v1

상태: **IMPLEMENTED**. 이 문서는 [OpenAPI](track1-seat-investigation-v1.yaml), Spring Controller 및 자동화 테스트와 일치한다. 좌석 일치는 저장된 예매 기록의 연결 근거일 뿐, 부정 거래 확정이 아니다.

## 가명화와 원장 반입

Track 1 수량 원장은 기존 `POST /api/v1/admin/events/{eventId}/track1-quantity/ledger-imports`를 사용한다. 요청의 `accountReference`, `reservationReference`, `ticketReference`, `paymentReference`, `addressReference`, `deviceReference`, `ipReference`는 요청 처리 중에만 사용한다. Spring은 tenantㆍ목적ㆍ키 버전별 HMAC-SHA256 토큰을 만들고 원문을 MySQL, FastAPI 요청, 관리자 응답, 오류 응답에 저장하거나 반환하지 않는다.

동일 tenantㆍ동일 목적ㆍ동일 키 버전ㆍ동일 원문은 같은 토큰이 되고, tenantㆍ목적ㆍ키 버전 중 하나라도 다르면 다른 토큰이 된다. Track 1은 분석 목적 토큰과 별도로 Track 2 `RESERVATION_REFERENCE` 목적 토큰을 비공개로 저장한다. 이 토큰은 API 응답에 포함되지 않는다.

```json
{
  "performanceId": "11111111-1111-4111-8111-111111111111",
  "inputType": "INDIVIDUAL_TICKET",
  "sourceVersion": "synthetic-quantity-v1",
  "sourceAsOf": "2026-12-05T09:00:00Z",
  "quantityBasis": "PROVIDER_TICKET_LEDGER",
  "completeSnapshot": true,
  "rows": [{
    "accountReference": "synthetic-account-1",
    "reservationReference": "synthetic-reservation-19-8-12",
    "ticketReference": "synthetic-ticket-1",
    "purchasedAt": "2026-12-01T09:00:00Z",
    "quantity": 1,
    "quantityUnit": "TICKET",
    "ticketStatus": "ACTIVE",
    "deviceReference": "synthetic-device-1"
  }]
}
```

좌석은 Track 1에 중복 저장하지 않는다. 기존 `POST /api/v1/admin/events/{eventId}/reservation-imports`에 같은 **원본** `reservationReference`와 좌석을 완전한 snapshot으로 반입한다. Track 1과 Track 2의 `sourceVersion`은 서로 다른 원장 버전이므로 같을 필요가 없다.

```json
{
  "performanceId": "11111111-1111-4111-8111-111111111111",
  "sourceVersion": "synthetic-seat-ledger-v1",
  "completeSnapshot": true,
  "rows": [{
    "reservationReference": "synthetic-reservation-19-8-12",
    "ticketReference": "synthetic-ticket-1",
    "zone": "19구역",
    "row": "8열",
    "seat": "12번"
  }]
}
```

동일 참조라도 현재 `ACCEPTED`, `completeSnapshot=true`, tenant/event/performance, Track 2 sourceVersion 및 HMAC 키 버전이 모두 맞아야 한다. 목적이 확인되지 않은 legacy evidence, 키 버전 불일치, 불완전하거나 과거 원장은 재해석하지 않고 `LEDGER_UNAVAILABLE`이다.

## 군집 상세 조회

`GET /api/v1/admin/track1/clusters/detail`은 기존 `eventId`, `analysisRequestId`, `clusterId`를 그대로 요구한다. 선택적인 `performanceId`와 `sourceVersion`은 반드시 함께 보낸다. 하나만 보내거나 빈 `sourceVersion`이면 `400`이다.

```text
GET /api/v1/admin/track1/clusters/detail?eventId={eventId}&analysisRequestId={analysisRequestId}&clusterId={clusterId}&performanceId={performanceId}&sourceVersion=synthetic-seat-ledger-v1
```

좌석 조회를 요청하지 않으면 기존 응답을 유지하고 다음 문맥을 반환한다.

```json
"verificationContext": {
  "status": "NOT_REQUESTED",
  "basis": null,
  "performanceId": null,
  "sourceVersion": null
}
```

현재 완전 원장으로 대조되면:

```json
{
  "verificationContext": {
    "status": "CURRENT_COMPLETE",
    "basis": "RESERVATION_REFERENCE",
    "performanceId": "11111111-1111-4111-8111-111111111111",
    "sourceVersion": "synthetic-seat-ledger-v1"
  },
  "members": [{
    "auditId": 101,
    "ledgerSeat": {
      "status": "FOUND",
      "zone": "19구역",
      "row": "8열",
      "seat": "12번",
      "seats": [{"zone": "19구역", "row": "8열", "seat": "12번"}]
    }
  }]
}
```

한 예매참조에 여러 좌석이 있으면 `ledgerSeat.status`는 `FOUND`, scalar `zone`ㆍ`row`ㆍ`seat`은 `null`이고 `seats[]` 전체를 사용한다. 완전한 현재 원장에 참조가 없으면 `NOT_IN_LEDGER`와 빈 `seats[]`이다. 원장이 없거나 불완전하거나 버전ㆍ키ㆍ목적 검증에 실패하면 `verificationContext.status`와 모든 `ledgerSeat.status`가 `LEDGER_UNAVAILABLE`이며, 이를 `NOT_IN_LEDGER`로 바꾸지 않는다.

`TENANT_ADMIN`, `ANALYST`, `REVIEWER`, `APPROVER`만 조회할 수 있다. 다른 tenant/event/performance 또는 접근 불가한 자원은 `404`, 같은 범위에서 역할이 없으면 `403`이다. 내부 장애는 빈 좌석으로 변환하지 않는다. 응답에는 원본 예매참조ㆍ계정ㆍHMAC 토큰ㆍ원예매자ㆍobject key가 없다.

## 시연 순서

1. 합성 tenant/event/performance UUID를 서버에서 만들고 Track 1 수량 원장과 Track 2 좌석 원장을 각각 반입한다.
2. FastAPI v2 분석을 실행해 저장 군집의 `analysisRequestId`, `clusterId`를 얻는다.
3. 위 상세 API에 Track 2의 `performanceId`, `sourceVersion`을 같이 보내 `CURRENT_COMPLETE`와 `FOUND` 좌석을 확인한다.
4. 별도 Case 생성 시에는 기존 Track 2 후보와 동일 참조가 `LINKED`인지 다시 검증한다. 좌석 조회 자체는 Case 생성이나 `LINKED` 판정을 변경하지 않는다.
