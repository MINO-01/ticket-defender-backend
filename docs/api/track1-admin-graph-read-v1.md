# Track 1 관리자 그래프 조회 API

```http
GET /api/v1/admin/track1/clusters/graph?eventId=33333333-3333-4333-8333-333333333333&analysisRequestId=7f3c2a10-5b4e-4d6f-9a81-2c3d4e5f6071&clusterId=cluster-1
```

목록·상세와 동일하게 관리자 JWT, 활성 tenant membership, 허용 역할, event grant를 모두 검증한다. 다른 tenant/event의 결과는 `404`이며, `analysisRequestId`와 `clusterId`만으로 범위를 우회할 수 없다. 저장된 구성원이 없으면 `404`, 저장된 구성원이 200건을 초과하면 `413`으로 실패하며 빈 그래프로 바꾸지 않는다.

```json
{
  "analysisRequestId": "7f3c2a10-5b4e-4d6f-9a81-2c3d4e5f6071",
  "clusterId": "cluster-1",
  "algorithmVersion": "gds-2.6.6;projection-v2-account;score-v2-exp1",
  "coverage": "STORED_EVIDENCE_RECONSTRUCTION",
  "nodes": [
    {"id": "account-1", "label": "계정 1", "kind": "ACCOUNT"},
    {"id": "reservation-1", "label": "예매 1", "kind": "RESERVATION"},
    {"id": "signal-1", "label": "공유 기기 1", "kind": "SIGNAL", "signalType": "DEVICE_ID_HASH"}
  ],
  "edges": [
    {"id": "edge-reservation-1", "source": "account-1", "target": "reservation-1", "kind": "RESERVATION"},
    {"id": "edge-signal-1-1", "source": "account-1", "target": "signal-1", "kind": "SHARED_SIGNAL"}
  ],
  "warnings": [
    "This graph is reconstructed from stored MySQL evidence and is not the original GDS projection."
  ]
}
```

`ACCOUNT` 노드는 동일 계정의 여러 저장 예매에 재사용하고, `RESERVATION` 노드는 각 저장 구성원에 하나씩 연결한다. `SIGNAL` 노드는 서로 다른 저장 계정에서 실제로 일치한 device/IP HMAC 값에만 생긴다. `SIGNAL`에만 `signalType`이 있으며 값은 `DEVICE_ID_HASH` 또는 `IP_HASH`다. `ACCOUNT`와 `RESERVATION`의 `signalType`은 `null`로 직렬화되지 않는다. 계정 ID, 예매번호, HMAC 값·키는 응답의 ID·label·edge에 포함하지 않으며, 화면용 순번 별칭만 반환한다.

`coverage=STORED_EVIDENCE_RECONSTRUCTION`은 MySQL에 남은 근거로 만든 표시 그래프라는 뜻이다. v1 GDS 투영이 사용한 payment/address/device/IP 전체 신호와 요청 전체의 hub 제외 조건은 모두 저장되어 있지 않으므로, 이 응답을 원본 GDS 투영과 동일하다고 해석하면 안 된다. 이 한계는 `warnings`에도 항상 포함된다. risk score는 기존 cluster detail의 저장 값으로 별도 조회한다.
