# 아키텍처

WMS(`dozy-wms-api`)의 헥사고날(Ports & Adapters) 구조를 따른다. 결정 배경은 WMS ADR-0001, [ADR-0001](adr/0001-same-stack-as-wms.md), [ADR-0010](adr/0010-package-structure.md)에 있다.

## 패키지 구조

패키지 루트는 `com.dozycoffee.ims`이다. 최상위는 `global`(공통 모듈)과 업무 도메인 패키지로 나눈다.

```
src/main/kotlin/com/dozycoffee/ims
├── global                       // 전역 설정·공통 모듈 (아래 "global 패키지")
├── product                      // 상품 마스터
├── inventory                    // 재고(Inventory), Lot, 재고 이력(InventoryHistory)
├── reservation                  // 예약, 예약 항목·할당, 예약 이벤트 이력
├── adjustment                   // 조정(실사 조정·대사 보정), 대사 실행(reconciliation_run)
├── access                       // 직원의 창고 소속 사본(warehouse_access)
└── outbox                       // 이벤트 발행 (Outbox 저장, 발행기)
```

도메인 패키지의 내부 구조는 모두 같다.

```
{domain}
├── adapter
│   ├── in
│   │   ├── web
│   │   │   ├── request          // 요청 DTO (toCommand() 변환)
│   │   │   └── response         // 응답 DTO (from(result) 변환)
│   │   ├── event                // 이벤트 수신 어댑터
│   │   └── scheduler            // 배치 트리거 (유통기한 스캔, 예약 만료 등)
│   └── out
│       ├── persistence          // XxxEntity, XxxR2dbcRepository, XxxPersistenceAdapter
│       ├── event                // 이벤트 발행 어댑터 (Outbox 저장 등)
│       └── client               // 외부 서비스 호출 어댑터 (WMS 등)
├── application
│   ├── port
│   │   ├── in
│   │   │   ├── command          // UseCase 입력
│   │   │   └── result           // UseCase 출력
│   │   └── out                  // XxxRepository 등 포트 인터페이스
│   └── service                  // XxxService (여러 UseCase를 구현, 트랜잭션 경계)
└── domain
    ├── model                    // 도메인 모델 (순수 Kotlin)
    ├── enumeration              // 도메인 열거형
    ├── exception                // XxxErrorCode, 규칙별 예외
    ├── valueobject              // 값 객체
    └── service                  // 도메인 서비스
```

패키지 이름은 WMS 코드와 같다(`enumeration`, `port/in`, `port/out`). `in`은 Kotlin 예약어라 백틱으로 감싼다.

## 이름 규칙

| 요소 | 이름 | 예 |
|------|------|----|
| UseCase | 동작마다 인터페이스 하나 (`port/in`) | `RegisterProductUseCase` |
| Command / Result | `RegisterProductCommand`, `ProductResult` | `port/in/command`, `port/in/result` |
| 출력 포트 | `XxxRepository` 인터페이스 (`port/out`) | `ProductRepository` |
| 서비스 | `XxxService`, 같은 도메인의 UseCase 여러 개를 구현 | `ProductService` |
| 영속성 엔티티 | `XxxEntity` | `ProductEntity` |
| Spring Data 저장소 | `XxxR2dbcRepository` | `ProductR2dbcRepository` |
| 영속성 어댑터 | `XxxPersistenceAdapter`, 출력 포트를 구현 | `ProductPersistenceAdapter` |
| 도메인 모델 | `create(...)`로 검증해 생성, `reconstitute(...)`로 저장소에서 복원 | `Product` |
| 오류 | 도메인별 `XxxErrorCode` enum, 호출부가 구분할 규칙마다 예외 클래스 | `ProductErrorCode`, `ProductNotFoundException` |
| REST 경로 | `/api/v1/{복수형 리소스}` | `/api/v1/products` |

## 레이어 규칙

- 의존 방향은 `adapter → application → domain`이다.
- `domain`은 `org.springframework`, `io.r2dbc`, `application`, `adapter`를 import하지 않는다. 순수 Kotlin이다.
- REST Controller, 이벤트 수신, 스케줄러는 모두 `adapter/in`에서 같은 UseCase(`application/port/in`)를 호출한다. 트리거가 달라도 도메인 로직은 한 곳에 둔다.
- 트랜잭션 경계는 `application/service`에 둔다. Controller에는 비즈니스 로직을 두지 않는다.
- 이벤트 발행과 외부 서비스(WMS, OMS, `dozy-auth`) 호출은 `application/port/out`의 포트로 추상화하고 `adapter/out`이 구현한다. 도메인과 서비스는 Kafka나 HTTP 클라이언트를 알지 못한다.

## 도메인 모델과 영속성 엔티티

- **도메인 모델**(`domain/model`)은 비즈니스 규칙과 불변식을 가진 순수 Kotlin 클래스이고 감사 필드(`createdAt` 등)를 모른다.
- **영속성 엔티티**(`adapter/out/persistence`의 `XxxEntity`)는 `BaseEntity`(또는 `SoftDeletableEntity`)를 상속해 감사 컬럼을 자동으로 채운다. 비즈니스 로직을 두지 않는다.
- 영속성 어댑터가 `XxxEntity.from(model)`과 `toDomain()`으로 둘을 변환한다. 갱신할 때는 기존 엔티티에서 생성 정보를 보존한다(`copyAuditFieldsFrom`).
- 삭제 여부가 도메인 규칙에 필요하면 도메인 모델에 `deleted` 같은 필드를 명시한다. 삭제 시각·삭제자 같은 감사 정보는 영속성에만 둔다.
- 응답에 생성·수정 시각이 필요하면 도메인 모델이 읽기 전용 값으로 명시해서 들고 있게 한다.

## 도메인 간 호출

- 다른 도메인의 기능은 **그 도메인의 `application/port/in`(UseCase, Command, Result)만** 호출한다. 다른 도메인의 Repository, 영속성 엔티티, 도메인 모델을 직접 쓰지 않는다.
- 예: 예약이 재고 행을 잡을 때 `reservation`의 서비스가 `inventory`의 UseCase를 호출한다. 두 호출은 같은 트랜잭션 안에서 실행된다.
- 도메인 간 의존이 순환하지 않도록 방향을 정한다(`reservation` → `inventory` → `product`).

## global 패키지

| 패키지 | 내용 |
|--------|------|
| `common` | `BaseEntity`(감사 컬럼), `SoftDeletableEntity` — 영속성 엔티티만 상속한다 |
| `config` | `Clock` 빈(Asia/Seoul), R2DBC Auditing 설정 |
| `error` | `ErrorCode`, `BusinessException` 계층, `GlobalExceptionHandler`(Problem Details), `TraceIdWebFilter` |
| `persistence` | `translatingDuplicateKey` (중복 키 위반 변환) |
| `security` | `Actor`, `CurrentActorProvider`, `ActorContext`, `SecurityConfig`(보안 체인), `SecurityContextActorProvider`(토큰 기반, `local` 외), `LocalActorProvider`(`local` 전용), `ImsRole`·`ImsAuthorize`(role과 `@PreAuthorize` 식) |

결정 배경은 [ADR-0009](adr/0009-global-module-decisions.md)에 있다.

## 설정

- 설정은 `application.yaml`에서 환경변수로 받는다. 비밀(DB 비밀번호 등)에는 기본값을 두지 않는다.
- 예약 TTL 상한(채널별), 조정 승인 임계치 같은 업무 설정값은 테이블이 아니라 애플리케이션 설정으로 둔다.

## 아키텍처 테스트 (예정)

위 규칙은 Konsist로 검사한다(F-006): `domain`의 Spring·R2DBC import 금지, `adapter → application → domain` 의존 방향, 다른 도메인은 `application.port.in`만 참조, Entity와 DTO 이름 규칙.
