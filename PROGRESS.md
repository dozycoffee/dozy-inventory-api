# PROGRESS

## 현재 상태

- 설계 확정: 서비스 경계, 핵심 원칙, 시나리오 결정, 기술 결정(ADR-0001~0017), ERD(테이블 13개)가 확정됐다. 구현 기준 원본은 이 레포의 `docs/`이다.
- 완료: F-001(골격), F-002(GitHub 저장소 설정), F-003(Flyway 마이그레이션 V1·V2), F-004(전역 공통 모듈), F-005(상품 마스터), F-006(Konsist 아키텍처 테스트), F-007(재고 모델), F-020(인증 연동), F-022(API 문서화).
- 패키지 구조 확정(ADR-0010): 도메인 모델과 영속성 엔티티 분리(도메인은 감사 필드를 모름), 다른 도메인은 UseCase로만 호출, 도메인 패키지 6개, `/api/v1`. F-005(상품 마스터)는 사용자가 직접 구현한다.
- `main`은 보호되어 있다(PR과 CI `verify` 필수, Rebase and merge만 허용). Kotlin·Spring Boot·Gradle wrapper의 마이너·메이저 업데이트는 Dependabot에서 제외했다(WMS와 버전 정렬).
- 로드맵 변경: 인증 연동(F-020)을 첫 API(F-008) 앞으로 옮겨 완료했다. `dozy-auth` 서버에 `inventory` audience, role 3개, system client를 등록하는 것은 개발·테스트의 선행 조건이 아니다(테스트는 `auth-test`). 실제 서버와 연동을 확인하거나 배포하기 전에 관리자 API로 등록한다.
- 미확정: `reservation.channel` 값(채널 종류), 캐시 도입 시점.

## 세션 로그

### 2026-10-05

- Notion에서 시나리오, 핵심 원칙, 기술 결정, ERD를 확정하고 `docs/`로 이식했다.
- Gradle(Kotlin DSL) 프로젝트, 컨텍스트 로드 테스트, Spotless(ktlint), 로컬 환경(compose, `.env`), `scripts/verify.sh`, CI, Dependabot, 이슈·PR 템플릿, `AGENTS.md`/`CLAUDE.md`, `docs/`를 만들어 `main`에 push했다.
- Dependabot PR #1(Actions)을 머지하고 #3(mockito-kotlin 6.4.0)과 #6(Gradle 9.8.0)은 WMS와 맞추려고 닫았다. Kotlin·Spring Boot·Gradle wrapper 제외 규칙을 추가했다.
- GitHub 저장소 설정을 적용했다(라벨 4개에 아이콘, 머지 방식, `main` 브랜치 보호).
- F-003: ERD를 Flyway V1(테이블)·V2(외래키)로 옮기고 멱등 키 `VARCHAR(100) ascii_bin`·Lot 번호 대소문자 구분을 확정했다(ERD-07, ERD-08). 스키마 제약 테스트 15개를 추가했다.

### 2026-10-06

- F-004: `global` 모듈(오류 응답, `Clock`, 감사, `Actor`, 중복 키 변환)을 구현했다. 결정은 ADR-0009: `Clock` 주입, `INV_` 오류 코드 접두사, `local` 프로필 전용 `Actor`(그 외 프로필은 기동 실패), Spring Data Auditing.
- `Actor`를 Reactor Context로 전달하는 `ActorContext`를 추가했다. 스케줄러는 `ActorContext.with(SystemActor)`로 감싸 실행한다.
- 테스트 28개를 추가했다(오류 응답 12, 감사 5, `Actor` 6, 중복 키 3, 프로필 가드 2). 시각·`Actor` 처리를 일부러 고장 내 테스트가 실패하는 것을 확인했다.
- 패키지 구조를 확정하고(ADR-0010) `docs/architecture.md`를 갱신했다. WMS와 달라진 점은 도메인 모델이 `BaseEntity`를 상속하지 않는 것이다.
- `dozy-auth` 최신 상태를 확인했다: audience·role·system client 등록 관리 API가 생겼고, 스타터 0.2.1(WebFlux, system token 클라이언트)이 공개돼 있다. system 토큰에는 클라이언트 이름이 없고 `principalId`(UUID)만 있다.

- F-005: 상품 마스터를 완료했다. 도메인 모델(`Product`, 상품 코드는 trim·대문자로 정규화), application 계층(등록, 상태 변경, ID 조회, 코드·분류·상태 필터 목록 조회), 영속성 어댑터(`BaseEntity`만 상속, 필터는 `R2dbcEntityTemplate` Criteria, 중복 키는 `DuplicateProductCodeException`으로 변환, enum과 CHECK 제약 일치 테스트), 로그만 남기는 이벤트 발행기, 웹 어댑터(`/api/v1/products`: 조회는 모든 role, 등록·상태 변경은 admin)를 구현했다. 상품 수정·삭제는 두지 않는다(상태는 INACTIVE로 대체). 상품 ID는 DB AUTO_INCREMENT.
- `DomainValidator.requireNotBlank`를 추가했고, `port/in` 백틱 패키지명을 위해 ktlint `package-name` 규칙을 껐다.
- `dozy-auth` 최신 상태를 확인했다: 스타터 0.2.1 이후 변경이 없고 WebFlux·role 인가·401/403·`auth-test`를 지원한다. IMS 연동에 필요한 기능은 모두 있다.

- F-020: `dozy-auth` 스타터 0.2.1을 연동했다(`SecurityConfig`, `SecurityContextActorProvider`, `InventoryRole`). 결정은 ADR-0011: role을 3개(`service`·`warehouse_manager`·`admin`)로 나누고 창고 범위는 `warehouse_access` 사본으로 판단, `requester_service`는 principalId(UUID) 저장, `local`은 토큰 없이 개발 사용자, CORS·공개 경로 없음. 빌드·CI에 `GPR_USER`·`GPR_TOKEN`이 필요하다.
- F-022: REST Docs + `restdocs-api-spec`으로 컨트롤러 테스트에서 OpenAPI 명세를 만들고 CI가 `openapi-spec` 아티팩트로 올린다(ADR-0013). 상품 API 4개를 문서화했고 새 API는 성공 응답 문서화가 필수다(`docs/testing.md`). `restdocs-api-spec`이 servlet 스택을 끌어와 빌드에서 제외한다. 정적 사이트 배포는 명세 공개 여부를 정한 뒤 정한다.
- F-006: Konsist 0.17.3으로 아키텍처 규칙을 테스트로 강제한다(ADR-0014). 레이어 의존 방향, 도메인 간 호출(다른 도메인은 `application/port/in`만), 이름·위치, 컨벤션(`!!`, `@Autowired`, `now()` 직접 호출, `@Transactional`·Reactor 위치, 프로퍼티·반환 타입 명시) 규칙을 `architecture` 패키지에 두었다. 규칙마다 일부러 위반 코드를 넣어 테스트가 실패하는 것을 확인했다.
- F-007: 재고 도메인 계층을 구현했다(ADR-0015). `Lot`(상태 판정은 기준일·임박 일수를 인자로), `Inventory`(`increase`·`decrease`·`reserve`·`release`·`ship`·`hold`·`releaseHold`, 수량 불변식과 예약 불가 원인 판별), `InventoryHistory`(불변, 유형별 변동량 부호 규칙), `IdempotencyKey`, `InventoryKey`와 enum 4개, 예외·오류 코드. 무작위 연산으로 수량 불변식도 검증한다. Konsist 규칙은 도메인 모델의 읽기 전용 감사 값(`val createdAt`)을 허용하도록 조정했다. 영속성 어댑터는 별도 PR로 구현했다(ADR-0016): 출력 포트 3개(`LotRepository`, `InventoryRepository`, `InventoryHistoryRepository`), `INSERT ... ON DUPLICATE KEY UPDATE` 입고 upsert, 조건부 UPDATE(영향 행 0이면 도메인 모델로 원인 판별), 중복 멱등 키는 예외로 롤백, 감사 기반 클래스 한 줄기 상속(`CreatedAuditEntity` ← `BaseEntity` ← `SoftDeletableEntity`). 실제 MySQL로 SQL과 모델 규칙 일치, enum·CHECK 일치, 코루틴 50개 동시성 3개 시나리오를 검증한다. `utf8mb4_bin`은 PAD SPACE라 끝 공백만 다른 Lot 번호는 같은 Lot으로 취급된다(ERD-08에 기록).
- F-008(진행 중): 입고 확정 반영의 application 계층을 구현했다(ADR-0017). `ConfirmInboundUseCase`/`InboundService`(상품 확인 → Lot 찾기·등록 → 재고 upsert → `INBOUND` 이력 → 이벤트, 한 트랜잭션), 멱등 재요청은 저장된 이력으로 처음 응답과 같은 결과를 반환, 같은 키에 다른 내용·Lot 날짜 불일치는 409, 같은 키·같은 Lot의 동시 50개 요청도 정확히 처리한다(실제 MySQL). 이벤트는 출력 포트와 로그 구현이다. 웹 어댑터(`POST /api/v1/inbound-receipts`, `inventory:service`만)와 API 문서화는 다음 PR이다.

## 다음 세션에서 할 일

1. F-018 구현 때 `LoggingProductEventPublisher`를 Outbox 저장 구현으로 교체한다(지금은 이벤트가 실제로 발행되지 않는다).
2. 입고 확정 반영(F-008)의 웹 어댑터 PR, 그다음 재고 조회(F-009), 예약(F-010). 테스트는 `@Nested`로 묶는다.
3. 실제 서버 연동 확인·배포 전에 `dozy-auth`에 `inventory` audience, `inventory:service`·`inventory:warehouse_manager`·`inventory:admin` role, system client(`svc-wms`, `svc-oms`, `svc-store`)를 등록한다(사용자 작업).
4. Dependabot이 제외 규칙대로 동작하는지 다음 점검 때 확인한다.
