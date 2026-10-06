# 코딩 컨벤션

WMS(`dozy-wms-api`)의 Kotlin 컨벤션을 이어받는다. 전체 코드베이스는 Kotlin이다.
서식은 Spotless(ktlint)가 강제하고(`./gradlew spotlessApply`, `.editorconfig`), 아래는 도구가 잡지 못하는 의미 규칙이다.

## 타입

- 클래스 프로퍼티, 함수 파라미터, 함수 반환 타입은 타입 추론에 맡기지 않고 항상 명시한다.
- 함수/메서드 **본문 내부**의 지역 변수(`val`/`var`)는 타입 추론을 허용한다.

## Entity

- Entity는 `data class`로 선언하지 않는다. `copy()`가 `create()` 팩토리의 불변식 검증을 우회하기 때문이다.
- Entity는 일반 `class` + `companion object`의 `fun create(...)` 팩토리로 만들고, `equals`/`hashCode`는 식별자(`id`) 기준으로 직접 오버라이드한다.
- 비즈니스 로직과 불변식은 Entity 안에 둔다. setter를 노출하지 않고 의미 있는 도메인 메서드로 상태를 바꾼다.
- DTO는 `data class`를 사용한다(불변, 식별자 없음, 불변식 검증 불필요).
- 상태·분류 값은 Kotlin enum으로 정의하고 DB에는 상수 이름(`name`)을 저장한다. enum을 바꾸면 해당 CHECK 제약 마이그레이션이 필요하며, enum과 CHECK의 일치를 검증하는 테스트를 둔다.

## Null 안전성

- `!!`를 쓰지 않는다. nullable은 `?.` / `?:` / 스마트 캐스트로 처리한다. 부득이하면 이유를 주석으로 남긴다.
- Java 라이브러리(Spring, R2DBC)를 호출하는 경계에서 넘어오는 플랫폼 타입은 경계에서 즉시 nullable 여부를 명시해 처리한다.

## 가시성과 확장 함수

- Kotlin 기본 접근제어자는 `public`이다. 레이어 경계를 지키려면 `domain/model`의 내부 구현은 `internal`/`private`로 명시적으로 좁힌다.
- 확장 함수는 어댑터 계층의 변환(Entity ↔ 영속성 모델, 도메인 모델 ↔ DTO)에만 쓴다. 비즈니스 로직과 불변식 검증을 확장 함수로 domain 밖에 두지 않는다.

## 비동기 처리

- Reactor(`Mono`/`Flux`) 대신 Coroutines(`suspend fun`, `Flow`)를 쓴다.
- Repository는 `CoroutineCrudRepository`를 쓰고, 단건 조회는 `T?`, 목록은 `Flow<T>`를 반환한다.
- 프레임워크가 Reactor 타입을 요구하는 경계(보안, Auditing 등)에서만 `kotlinx-coroutines-reactor`로 변환한다. 그 외 지점에 Reactor 타입을 새로 들이지 않는다.
- `DatabaseClient`는 `org.springframework.r2dbc.core`의 Kotlin 확장(`awaitRowsUpdated()`, `awaitOne()`, `awaitOneOrNull()`, `flow()`)으로 쓴다.
- `@Transactional` 서비스 안에서 `launch`/`async`로 새 코루틴을 띄우지 않는다(트랜잭션 컨텍스트가 공유되지 않는다).
- R2DBC 체인에서 blocking I/O를 하지 않는다.

## R2DBC

- 필요한 컬럼만 조회한다(불필요한 전체 엔티티 로딩 금지). 필요하면 DTO Projection을 쓴다.
- 루프 안에서 개별 쿼리를 반복하지 않는다(`IN` 절 또는 병렬화).
- R2DBC는 JPA의 cascade/orphanRemoval을 지원하지 않는다. 연관 엔티티는 각 도메인의 Repository로 저장 순서와 삭제 순서를 명시적으로 처리한다.
- 유니크 제약이 있는 저장은 사전 조회만 믿지 않는다. 동시 요청이 조회를 함께 통과하면 제약 위반이 500으로 나가므로, 중복 키 위반(MySQL 1062)만 도메인의 `Duplicate*Exception`으로 바꾸고 FK·CHECK 위반은 그대로 둔다.

## Spring

- 생성자 주입을 쓴다. 필드 주입을 쓰지 않는다.
- Controller는 요청·응답 처리에 집중하고 비즈니스 로직을 두지 않는다. 트랜잭션 경계는 Service에 둔다.

## 주석

- 코드가 표현하지 못하는 제약이나 의도만 주석으로 남긴다.
- 코드가 하는 일을 되풀이하는 주석을 쓰지 않는다.
- 시점이나 계획을 가리키는 주석("나중에 추가한다", "Step 2에서 이동")을 쓰지 않는다. 이전 계획은 ADR이나 PR에 둔다.

## 오류

- 비즈니스 오류는 `BusinessException` 계층(`DomainException`, `ApplicationException`)으로 던진다. 응답 변환은 `GlobalExceptionHandler`가 맡고 Controller에서 오류 응답을 직접 만들지 않는다.
- 필드 값의 단순 검증(null, 빈 값, 범위)은 `ErrorCode`만 등록하고 `InvalidDomainValueException`으로 던진다. 호출부나 테스트가 타입으로 구분할 규칙 위반(상태 전이, 수량 부족, NotFound, Duplicate 등)은 전용 예외 클래스를 만든다.
- `ErrorCode.code`는 범용 코드는 `dozy-auth` 에러 코드 표의 이름을, 도메인 코드는 `IMS_` 접두사를 붙인다(`IMS_PRODUCT_NOT_FOUND`). 새 코드를 추가할 때 다른 서비스와 겹치지 않는지 확인한다.
- 500 응답에는 내부 정보(스택, SQL, 클래스명)를 싣지 않는다.
- 유니크 제약이 있는 저장은 `translatingDuplicateKey`로 감싸 중복 키 위반(MySQL 1062)만 도메인 예외로 바꾼다. FK·CHECK 위반은 그대로 둔다. 멱등 키 중복은 오류가 아니라 이전 결과를 반환하는 흐름이다.

## 시간과 감사

- 시간은 `Clock` 빈을 주입받아 쓴다. 코드에서 `LocalDateTime.now()` 등을 직접 호출하지 않는다.
- Entity는 `BaseEntity`를 상속해 Spring Data Auditing이 감사 컬럼을 채우게 한다. `DatabaseClient`로 직접 쓰는 SQL은 Auditing이 동작하지 않으므로 `updated_at`/`updated_by`를 SQL에 직접 넣는다.
- 요청 밖 작업(스케줄러)은 `ActorContext.with(SystemActor) { ... }`로 감싸 실행해 감사 이름이 `system`이 되게 한다.

## 테스트 이름

- 테스트 메서드명은 백틱으로 한글 문장형 이름을 쓴다(`` `상품 등록 성공`() ``). 자세한 내용은 [testing.md](testing.md)를 따른다.
