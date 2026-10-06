# ADR-0010: 도메인 패키지 구조와 도메인 간 호출 규칙

## 상태

Accepted (2026-10-06)

## 배경 (Context)

첫 도메인(F-005, 상품 마스터)을 구현하기 전에 패키지 구조와 레이어 규칙을 확정한다. WMS의 `product`와 `inventory` 패키지를 기준으로 삼았다.
WMS는 도메인 모델(`Product`)이 `SoftDeletableEntity`(Spring Data 어노테이션이 붙은 `BaseEntity`)를 상속해서, "도메인은 Spring에 의존하지 않는다"는 레이어 규칙이 사실상 지켜지지 않는다.
IMS는 예약과 재고처럼 서로 가깝게 얽힌 도메인이 있어 도메인 간 호출 규칙이 필요하다.

## 결정 (Decision)

1. **도메인 모델과 영속성 엔티티를 분리하고, 도메인은 감사 필드를 모른다.** `BaseEntity`는 영속성 엔티티(`XxxEntity`)만 상속한다. 도메인 모델은 순수 Kotlin이며 `domain`은 Spring·R2DBC를 import하지 않는다. 필요한 감사 값(예: 응답의 생성 시각)은 도메인에 읽기 전용 값으로 명시한다.
2. **다른 도메인은 그 도메인의 `application/port/in`(UseCase)만 호출한다.** Repository, 영속성 엔티티, 도메인 모델은 직접 쓰지 않는다. 호출은 같은 트랜잭션 안에서 실행한다.
3. **도메인 패키지는 `product` / `inventory`(Lot, 재고 이력 포함) / `reservation` / `adjustment`(대사 포함) / `access` / `outbox`로 나눈다.** Lot은 WMS처럼 `inventory`에 둔다.
4. **REST 경로는 `/api/v1/{복수형 리소스}`로 한다.** 호환이 깨지는 변경을 새 버전으로 낼 길을 열어둔다.
5. 그 밖의 구조와 이름 규칙은 WMS를 이어받는다: UseCase는 동작마다 인터페이스 하나, Command·Result는 `port/in/command`·`port/in/result`, 출력 포트는 `XxxRepository`, 영속성은 `XxxEntity` / `XxxR2dbcRepository` / `XxxPersistenceAdapter`, 열거형 패키지는 `enumeration`, 도메인 모델은 `create()`와 `reconstitute()` 팩토리.

## 결과 (Consequences)

- 얻는 것: 레이어 규칙을 Konsist로 기계적으로 검사할 수 있다(F-006). 도메인 규칙이 다른 도메인에 우회되지 않는다. 경로 버전을 나중에 넣느라 모든 클라이언트를 고칠 일이 없다.
- 감수하는 것:
  - 도메인 모델과 엔티티 사이의 매핑 코드(`from()`, `toDomain()`)가 늘어난다.
  - 응답에 감사 값이 필요하면 도메인에 명시적으로 옮겨야 한다.
  - `inventory`가 예약용 UseCase(재고 행 확보 등)를 제공해야 하고 `inventory`가 커질 수 있다.
  - `/api/v1` 때문에 WMS와 경로 형식이 다르다.
- WMS와 달라진 점: 도메인 모델이 `BaseEntity`를 상속하지 않는다.
