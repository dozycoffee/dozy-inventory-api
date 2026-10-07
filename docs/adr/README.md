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
| [0008](0008-repository-conventions.md) | 저장소 운영 규칙 | Accepted (머지 방식은 ADR-0012로 대체) |
| [0009](0009-global-module-decisions.md) | 전역 공통 모듈 설계 결정 | Accepted |
| [0010](0010-package-structure.md) | 도메인 패키지 구조와 도메인 간 호출 규칙 | Accepted |
| [0011](0011-auth-integration.md) | dozy-auth 인증 연동 | Accepted |
| [0012](0012-merge-commit-strategy.md) | PR은 머지 커밋으로 머지 | Accepted |
| [0013](0013-api-docs-with-rest-docs.md) | API 명세는 REST Docs로 만든 OpenAPI 파일로 관리 | Accepted |
| [0014](0014-konsist-architecture-tests.md) | 아키텍처 규칙은 Konsist 테스트로 강제 | Accepted |
| [0015](0015-inventory-domain-model.md) | 재고 도메인 모델 규칙 | Accepted |

ERD 설계 결정(ERD-01~06)은 [erd.md](../erd.md)에, 업무 시나리오 결정은 [scenarios.md](../scenarios.md)에 있다.

## 형식

```
# ADR-XXXX: 제목
## 상태
## 배경 (Context)
## 결정 (Decision)
## 결과 (Consequences)   — 얻는 것 / 감수하는 것
```
