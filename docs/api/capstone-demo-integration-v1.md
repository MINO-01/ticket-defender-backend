# 관리자 시연 연동 API v1

이 문서는 Backend에 실제 구현된 조회 계약만 다룹니다. 모든 응답은 JSON camelCase와 UTC ISO-8601 시각을 사용합니다. `tenantId`는 요청 값으로 받지 않으며, 서버가 JWT 멤버십과 `eventId` 또는 저장 자원의 tenant/event 범위를 대조합니다. 다른 tenant·공연·비가시 자원은 `404 RESOURCE_NOT_FOUND`, 같은 범위에서 역할이 부족하면 `403`입니다.

## 구현 완료

| 화면/용도 | Method · Endpoint | 권한 | 응답 핵심 |
|---|---|---|---|
| 제보 목록 | `GET /api/v1/admin/fan-reports?eventId&page&pageSize&intakeStatus` | ANALYST, REVIEWER | `items[].fanReportId`, `receiptNumber`, 접수/증거 수 |
| 제보 상세 | `GET /api/v1/admin/fan-reports/{fanReportId}` | ANALYST, REVIEWER | `receiptNumber`, 제출 내용, 증거 메타데이터 |
| Track 1 군집 상세 | `GET /api/v1/admin/track1/clusters/detail?eventId&analysisRequestId&clusterId` | 기존 Track 1 조회 역할 | 구성원, `relationship` |
| Track 2 실행 이력 | `GET /api/v1/admin/analysis-runs?eventId&page&pageSize` | ANALYST, REVIEWER | 실제 `ListingContextAnalysis` 행의 실행/분석 상태, 실패 코드, 제보·증거 참조 |
| 사건 업무 연결 | `GET /api/v1/cases/{caseId}/workflow` | 기존 Case 상세 조회 범위 | `investigation`, `nextStep`, `report`, `approval`, `officialPdf` |
| 승인 감사 이력 | `GET /api/v1/cases/{caseId}/approval-audit-events?page&pageSize` | 기존 Case 상세 조회 범위 | append-only 승인 요청·영수증·결정·철회/무효화 이력 |
| 안전 운영 상태 | `GET /api/v1/admin/operations/settings?eventId` | TENANT_ADMIN | 기능 활성 상태만, 비밀·URL·버킷 제외 |

`receiptNumber`는 접수 건을 찾는 업무용 번호일 뿐 제보자 신원을 의미하지 않습니다. 기존 `fanReportId`는 내부 UUID 참조로 유지됩니다. 응답에는 lookup/recovery secret, digest, object key/version이 없습니다.

`relationship`은 `MacroAuditEvidence`에 저장된 device/IP 값만으로 계산합니다. `sharedSignals[].sharedMemberCount`는 같은 저장 신호값을 가진 저장 구성원 수이며, `verifiedConnectionCount`는 각 반복 신호값이 만드는 비순서 구성원 쌍의 합입니다. 신호 원문/HMAC은 반환하지 않습니다. `unavailableSignalTypes`의 `PAYMENT_HASH`, `ADDRESS_HASH`는 이 군집 증거에 저장된 관계 근거가 아니므로 생성하거나 소급 추정하지 않았다는 뜻입니다. `memberCoverage=STORED_CLUSTER_MEMBERS_ONLY`이며 원본 전체 군집을 복원하거나 동일인·조직·부정행위를 판정하지 않습니다. `cluster.riskScore`는 저장된 Louvain 점수이며 불법 확률이나 자동 적발 결과가 아닙니다.

`analysis-runs`는 Track 2 이미지 분석의 저장된 실행 이력만 제공합니다. Track 1에는 모든 요청(무결과·실패 포함)을 표현하는 `AnalysisJob` 엔터티가 없으므로, 이 API를 통합 분석 작업 전체 이력으로 표시하면 안 됩니다. 실패 실행은 `executionStatus`와 `errorCode` 그대로 반환되며 빈 결과로 바꾸지 않습니다.

`workflow.report`, `approval`, `officialPdf`는 연결된 실제 저장 행이 없으면 `null`입니다. `nextStep`은 화면 안내용 실제 업무 단계이지 실행 권한이나 PDF 적격성 판정이 아닙니다. 공식 PDF는 기존 승인 적격성·증거 재검증·합성 격리 게이트를 별도로 통과해야 합니다. 특히 `APPROVAL_BASIS_BLOCKED`, 근거 변경, 자기 승인, 승인 없는 발급을 우회하지 않습니다.

`operations/settings`는 `fanReportIntakeEnabled`, `track2ListingAnalysisConfigured`, Track 1 근거 범위, PDF의 `SYNTHETIC_ISOLATION_ONLY` 또는 `BLOCKED`, `auditorAccessState`만 반환합니다. JWT/API key/HMAC·DB 비밀번호·내부 URL·버킷·object key는 반환하지 않습니다. AUDITOR 역할은 현재 Backend enum과 확정 권한 정책에 없으므로 구현하거나 권한을 부여하지 않았습니다.

## Frontend 연결

- 제보 목록/상세에는 `receiptNumber`를 “접수번호”로 표시하고 신원 필드로 라벨링하지 않습니다.
- 군집 상세에는 `relationship.sharedSignals`를 근거 요약으로, `normalSharingNotice`를 항상 함께 표시합니다. 값·토큰 표시 UI를 만들지 않습니다.
- 분석 화면에는 `coverage` 문구를 노출해 Track 2 이력 전용임을 표시하고, 실패 행을 숨기지 않습니다.
- Case 상세는 기존 Case/Report/Approval/PDF 개별 라우트를 대체하지 않습니다. `workflow`의 UUID를 각 기존 상세·발급 상태 라우팅에 사용하고 `nextStep`으로 버튼 안내만 합니다.
- 승인 감사에는 `reason`과 상태 전이만 표시합니다. review receipt token, manifest/content digest, PDF 저장소 정보를 기대하면 안 됩니다.

## 미구현/정책 결정 필요

- 범용 `AnalysisJob` 생성·재시도·전체 Track 1 실행 이력은 저장 모델과 비동기 작업 정책이 없어 구현하지 않았습니다.
- AUDITOR 읽기 범위와 운영 설정 권한은 정책 확정 후 별도 역할/감사 범위 설계가 필요합니다.
- 실운영 공식 PDF 발급 권한은 구현하지 않았습니다. 기존 합성 격리 제한만 유지합니다.
