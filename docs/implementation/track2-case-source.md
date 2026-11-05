# Track 2 후보 실행 Case 출처 계약

`POST /api/v1/cases`는 선택적으로 `candidateRunIds`를 받는다. 각 ID는 서버가 다시 조회하며,
원본 예매번호, reference HMAC, 키 버전 또는 비밀키는 요청·저장 출처·응답에 포함하지 않는다.

- 후보 실행은 terminal이고 tenant/event, fan report, evidence, analysis 소속이 일치하며 현재
  applicability가 `CURRENT`일 때만 새 Case에 연결된다.
- 후보 실행의 fan report와 evidence asset은 같은 Create 요청의 기존 출처로도 명시되어야 한다.
  따라서 후보 실행은 `GRAPH_CLUSTER`/`FAN_REPORT`/`COMBINED`의 필수 출처 규칙을 우회하지 않는다.
- `MATCH_CANDIDATE`는 조사 참고 결과일 뿐 부정 거래 확정이 아니다. `NO_MATCH`, `AMBIGUOUS`,
  `UNVERIFIABLE`, `LOOKUP_FAILED`, `ANALYSIS_FAILED`의 status/reason은 변환하지 않고 보존한다.
- Case 출처에는 run ID, 분석/evidence digest·revision, performance link/verified performance,
  reservation source/batch/version, 결과·opaque candidate ID를 immutable binding으로 저장한다.
- 기존 Case에 출처를 추가하는 API는 제공하지 않는다. PENDING/승인 Case의 근거 변경 정책은 이번
  범위에서 확정하지 않았으며 새 근거는 별도 후속 Case/상태 전이 계약이 필요하다.

후보가 없는 Snapshot은 기존 `manifestSchemaVersion=1` 직렬화와 digest를 유지한다. 후보 출처가
포함된 새 Snapshot은 schema 2이며, 현재 applicability 또는 어떤 immutable binding이라도 변경되면
재검증은 `SOURCE_CHANGED`로 실패한다. 과거 Snapshot, ReportVersion 및 ApprovalDecision은 수정하지
않고, 승인·발급 적격성은 현재 manifest 재검증 실패를 stale/blocked로 처리한다.
