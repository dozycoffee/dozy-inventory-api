# ADR (Architecture Decision Records)

설계 결정과 그 배경을 기록한다. 번호는 순서대로 부여하고, 결정이 바뀌면 기존 문서를 고치지 않고 새 ADR로 대체한다(상태를 `Superseded by ADR-XXXX`로 바꾼다).

| ADR | 제목 | 상태 |
|-----|------|------|
| [0001](0001-same-stack-as-wms.md) | WMS와 동일한 기술 스택 | Accepted |
| [0002](0002-kafka-with-outbox.md) | 메시지 브로커는 Kafka, 이벤트는 Outbox로 발행 | Accepted |
| [0003](0003-reservation-concurrency-conditional-update.md) | 예약 동시성은 DB 원자적 조건부 갱신 | Accepted |
| [0004](0004-master-data-ownership.md) | 마스터 데이터 소유 | Accepted |
| [0005](0005-separation-without-data-migration.md) | WMS에서 IMS로 이관 없이 분리 | Accepted |
| [0006](0006-service-authentication-and-warehouse-access.md) | 서비스 간 인증과 창고 접근 | Accepted (role 구성은 ADR-0011로 대체) |
| [0007](0007-no-tracking-after-outbound.md) | 출고 이후 재고는 추적하지 않는다 | Accepted |
| [0008](0008-repository-conventions.md) | 저장소 운영 규칙 | Accepted (머지 방식은 ADR-0012로, 마이그레이션 수정 규칙은 ADR-0021로 대체·완화) |
| [0009](0009-global-module-decisions.md) | 전역 공통 모듈 설계 결정 | Accepted |
| [0010](0010-package-structure.md) | 도메인 패키지 구조와 도메인 간 호출 규칙 | Accepted |
| [0011](0011-auth-integration.md) | dozy-auth 인증 연동 | Accepted |
| [0012](0012-merge-commit-strategy.md) | PR은 머지 커밋으로 머지 | Accepted |
| [0013](0013-api-docs-with-rest-docs.md) | API 명세는 REST Docs로 만든 OpenAPI 파일로 관리 | Accepted |
| [0014](0014-konsist-architecture-tests.md) | 아키텍처 규칙은 Konsist 테스트로 강제 | Accepted |
| [0015](0015-inventory-domain-model.md) | 재고 도메인 모델 규칙 | Accepted |
| [0016](0016-inventory-persistence.md) | 재고 영속성 규칙 | Accepted |
| [0017](0017-inbound-confirmation.md) | 입고 확정 반영 규칙 | Accepted |
| [0018](0018-rename-ims-to-inventory.md) | 서비스 이름을 IMS에서 inventory로 변경 | Accepted |
| [0019](0019-inbound-receipt-api.md) | 입고 확정 API 형태 | Accepted |
| [0020](0020-availability-query.md) | 가용 재고 조회 규칙 | Accepted |
| [0021](0021-consolidate-migrations-before-deployment.md) | 배포 전 마이그레이션 통합 | Accepted |
| [0022](0022-reservation-and-allocation.md) | 예약 생성과 Lot 할당 규칙 | Accepted |
| [0023](0023-reservation-state-transitions.md) | 예약 상태 전이(확정·해제·연장·만료) 규칙 | Accepted |
| [0024](0024-outbound-confirmation.md) | 출고 확정 반영 규칙 | Accepted |
| [0025](0025-outbox-event-publishing.md) | Outbox 이벤트 저장과 Kafka 발행 규칙 | Accepted |
| [0026](0026-stock-adjustment.md) | 실사 조정 반영 규칙 | Accepted |
| [0027](0027-lot-expiration-scan.md) | 유통기한 스캔 규칙 | Accepted |
| [0028](0028-disposal-confirmation.md) | 폐기 확정 반영 규칙 | Accepted |
| [0029](0029-return-restoration.md) | 반품 복귀 반영 규칙 | Accepted |
| [0030](0030-admin-inventory-query.md) | 관리자 재고 현황·상세·이력 조회 규칙 | Accepted |
| [0031](0031-lot-reallocation.md) | Lot 재할당과 할당 보류 규칙 | Accepted |
| [0032](0032-warehouse-access-control.md) | 창고 접근 제어 규칙 | Accepted |
| [0033](0033-reconciliation.md) | 대사 보정 규칙 | Accepted |
| [0034](0034-health-checks.md) | 헬스 체크와 graceful shutdown | Accepted |
| [0035](0035-container-image.md) | 컨테이너 이미지 | Accepted |
| [0036](0036-configuration.md) | 설정과 운영 프로필 | Accepted |
| [0037](0037-outbox-dead-letter.md) | Outbox 데드 레터(영구 실패 이벤트 격리) | Accepted |
| [0038](0038-common-event-format.md) | 공통 이벤트 형식(envelope) | Accepted |
| [0039](0039-event-metadata-in-payload.md) | 이벤트 메타데이터를 값(payload)에만 둔다 | Accepted |
| [0040](0040-cors-for-admin-console.md) | 어드민 콘솔을 위한 CORS | Accepted |
| [0041](0041-material-manager-role.md) | 원자재 담당 role(`material_manager`) | Accepted |

ERD 설계 결정(ERD-01~06)은 [erd.md](../erd.md)에, 업무 시나리오 결정은 [scenarios.md](../scenarios.md)에 있다.

## 형식

```
# ADR-XXXX: 제목
## 상태
## 배경 (Context)
## 결정 (Decision)
## 결과 (Consequences)   — 얻는 것 / 감수하는 것
```
