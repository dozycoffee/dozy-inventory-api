# 아키텍처

WMS(`dozy-wms-api`)의 헥사고날(Ports & Adapters) 구조를 따른다. 근거는 WMS ADR-0001과 [ADR-0001](adr/0001-same-stack-as-wms.md)이다.

> 🔶 아래 도메인 패키지 구분은 초안이다. 첫 도메인을 구현할 때 실제 코드에 맞춰 확정하고 이 문서를 고친다.

## 패키지 구조

패키지 루트는 `com.dozycoffee.ims`이다.

```
src/main/kotlin/com/dozycoffee/ims
├── global                       // 전역 설정·공통 모듈 (BaseEntity, 설정, 오류 처리)
├── product                      // 상품 마스터
├── inventory                    // 재고(Inventory), Lot, 재고 이력(InventoryHistory)
├── reservation                  // 예약, 예약 항목·할당, 예약 이벤트 이력
├── adjustment                   // 조정(실사 조정·대사 보정), 대사 실행
├── access                       // 직원의 창고 소속 사본
└── outbox                       // 이벤트 발행 (Outbox 저장, 발행기)

# 각 도메인의 내부 구조
{domain}
├── adapter
│   ├── in
│   │   ├── web                  // REST Controller, Request/Response DTO
│   │   ├── event                // 이벤트 수신 어댑터
│   │   └── scheduler            // 배치 트리거 (유통기한 스캔, 예약 만료 등)
│   └── out
│       └── persistence          // R2DBC 영속성 어댑터
├── application
│   ├── port
│   │   ├── in                   // UseCase 인터페이스, Command, Result
│   │   └── out                  // Port 인터페이스
│   └── service                  // 애플리케이션 서비스 (UseCase 구현체, 트랜잭션 경계)
└── domain
    ├── model                    // 도메인 모델 (순수 Kotlin 클래스)
    ├── enums
    ├── exception
    ├── valueobject
    └── service                  // 도메인 서비스
```

## 레이어 규칙

- 의존 방향은 `adapter → application → domain`이다. `domain`은 `application`·`adapter`·Spring에 의존하지 않는다.
- REST Controller, 이벤트 수신, 스케줄러는 모두 `adapter/in`에서 같은 UseCase(`application/port/in`)를 호출한다. 트리거가 달라도 도메인 로직은 한 곳에 둔다.
- 트랜잭션 경계는 `application/service`에 둔다. Controller에는 비즈니스 로직을 두지 않는다.
- 비즈니스 규칙과 불변식은 도메인 모델(Entity)에 둔다. 확장 함수나 서비스로 도메인 밖에 옮기지 않는다.
- 이벤트 발행은 `application/port/out`의 포트로 추상화한다. Kafka는 어댑터 구현 세부사항이며 도메인과 서비스는 브로커를 알지 못한다.
- 외부 서비스(WMS, OMS, `dozy-auth`)와의 통신도 포트와 어댑터로 감싼다.

## 설정

- 설정은 `application.yaml`에서 환경변수로 받는다. 비밀(DB 비밀번호 등)에는 기본값을 두지 않는다.
- 예약 TTL 상한(채널별), 조정 승인 임계치 같은 업무 설정값은 테이블이 아니라 애플리케이션 설정으로 둔다.
