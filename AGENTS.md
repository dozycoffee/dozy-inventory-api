# AGENTS.md

이 레포에서 작업하는 AI 에이전트(Claude Code 등)와 개발자를 위한 안내서다. `CLAUDE.md`는 이 파일을 가져온다.
규칙과 설계의 상세는 `docs/`에 있고, 이 파일은 지도 역할만 한다.

## 프로젝트 개요

**inventory 서비스**는 DOZY COFFEE 원부자재 재고의 단일 진실 공급원(SSOT)이다.
"무엇이 몇 개 있고, 그중 몇 개를 약속할 수 있는가"를 책임지고, WMS는 "그 물건이 창고 안 어디에 있고 어떻게 움직이는가"를 책임진다.
OMS·가맹점 서비스(Store)가 같은 재고를 바라보므로 가용 재고 계산과 예약은 inventory 서비스 한 곳에서만 수행한다.

- 상태: 설계 확정, 도메인별 구현 진행 중. 진행 상황은 `PROGRESS.md`에 있다. 구현 순서는 `feature_list.json`을 따른다.
- 범위: inventory 서비스만 다룬다. WMS 쪽 변경은 WMS 프로젝트에서 처리한다.

## 기술 스택

| 분류 | 기술 |
|------|------|
| Language | Kotlin 2.3 (JVM 21) |
| Framework | Spring Boot 4.1 (WebFlux), Kotlin Coroutines |
| DB | MySQL 8.0, Spring Data R2DBC, Flyway (스키마 `dozy_inventory`) |
| Architecture | 헥사고날 (패키지 루트 `com.dozycoffee.inventory`) |
| Messaging | Kafka + Outbox (구현 예정) |
| Auth | `dozy-auth` 스타터 0.2.1 (WebFlux, `auth-test`) |
| Build / Lint / Test | Gradle (Kotlin DSL), Spotless(ktlint), JUnit + Testcontainers(MySQL) |
| API 문서 | Spring REST Docs + restdocs-api-spec(OpenAPI 3), CI 아티팩트 |

## 명령어

```bash
./scripts/verify.sh                     # 서식 검사 + 빌드 + 테스트. 작업 완료 전 반드시 통과해야 한다 (Docker, GPR_USER·GPR_TOKEN 필요)
./gradlew test                          # 전체 테스트
./gradlew test --tests "com.dozycoffee.inventory.SomeTest"   # 특정 테스트
./gradlew spotlessApply                 # 서식 자동 정렬
./gradlew openapi3                      # 컨트롤러 테스트로 API 명세 생성 (build/api-spec/openapi3.yaml)

export GPR_USER=<깃허브 계정> GPR_TOKEN=<read:packages 토큰>   # dozy-auth 스타터 다운로드용. 저장소에 두지 않는다
cp .env.example .env                    # DB_PASSWORD를 채운다 (SPRING_PROFILES_ACTIVE=local 포함)
docker compose up -d                    # 로컬 MySQL(호스트 포트 3307)과 Kafka(호스트 포트 9092, Outbox 이벤트 발행 대상). 마이그레이션 파일이 합쳐졌으면(ADR-0021) `docker compose down -v`로 볼륨을 지우고 다시 만든다
./gradlew bootRun                       # 로컬 실행 (포트 8082, .env를 환경변수로 읽는다). local 프로필은 토큰 없이 개발 사용자로 동작하고, 그 외 프로필은 Auth 토큰이 필요하다
```

## 문서

필요한 시점에 해당 문서를 읽는다. 전부 미리 읽지 않는다.

| 문서 | 읽는 시점 |
|------|-----------|
| [docs/principles.md](docs/principles.md) | 새 기능을 설계하거나 구현하기 전. 서비스 경계와 핵심 원칙 |
| [docs/architecture.md](docs/architecture.md) | 패키지·레이어를 만들거나 코드를 어디에 둘지 정할 때 |
| [docs/erd.md](docs/erd.md) | 테이블, 마이그레이션, 쿼리를 다룰 때 |
| [docs/concurrency-and-idempotency.md](docs/concurrency-and-idempotency.md) | 예약·수량 변경·멱등 처리를 구현할 때 |
| [docs/conventions.md](docs/conventions.md) | Kotlin 코드를 작성하기 전 (타입 명시, Entity, 코루틴, 주석 등) |
| [docs/events.md](docs/events.md) | 이벤트 payload·토픽·키를 바꾸거나 구독자에게 알릴 때. 이벤트 계약 |
| [docs/testing.md](docs/testing.md) | 테스트를 작성하기 전 |
| [docs/scenarios.md](docs/scenarios.md) | 업무 시나리오와 경계 결정을 확인할 때 |
| [docs/adr/](docs/adr/README.md) | 결정의 배경이 필요할 때 |
| [docs/git-workflow.md](docs/git-workflow.md) | **커밋 생성·브랜치 생성·PR 작성 직전에 매번** 다시 읽는다 |

## 반드시 지킬 것

- 새 API를 만들면 컨트롤러 테스트에서 `.consumeWith(XxxApiDocs.xxx())`로 성공 응답을 문서화한다(명세 본문은 `XxxApiDocs` 객체에 둔다)(규칙은 `docs/testing.md`). 컨트롤러에 문서용 어노테이션을 붙이지 않는다.
- 변경하거나 추가한 기능에는 테스트가 따라야 한다. 완료를 선언하기 전에 `./scripts/verify.sh`를 통과시킨다.
- 비밀번호·키 같은 비밀은 코드나 설정 기본값에 넣지 않는다. 환경변수로 주입한다. 이 레포는 공개 레포다.
- inventory 서비스는 **입고 확정된 재고만** 다룬다. 입고 예정, Zone·Location, 출고 이후 재고(가맹점)는 inventory 서비스의 책임이 아니다.
- 수량을 바꾸는 모든 요청은 멱등 키를 가지며, 수량·이력·이벤트(Outbox)는 한 트랜잭션으로 저장한다.
- 예약은 DB 원자적 조건부 갱신으로 처리한다(읽고-계산하고-쓰기 금지). 여러 행은 `inventory_id` 오름차순으로 갱신한다.
- 다른 서비스의 데이터는 ID로만 참조한다. 다른 서비스 DB를 읽거나 JOIN하지 않는다.
- `domain` 패키지는 Spring·R2DBC를 import하지 않는다. 다른 도메인은 그 도메인의 `application/port/in`(UseCase)로만 호출한다.
- 아키텍처 규칙(레이어 의존, 도메인 간 호출, 이름·위치, 컨벤션)은 `architecture` 패키지의 Konsist 테스트가 검사한다. 새 접미사·패키지를 정하면 `NamingRuleTest`도 고친다(`docs/architecture.md`).
- 문서의 `🔶` 표시는 미확정 항목이다. 임의로 정하지 말고 사용자에게 묻는다.

## 작업 흐름

작업 전에 `AGENTS.md` → `docs/` → `feature_list.json` → `PROGRESS.md` 순서로 읽는다.
`feature_list.json`에서 `pending` 작업의 의존성을 확인하고 하나를 골라 `in_progress`로 바꾼 뒤 구현·검증하고,
완료 기준을 만족하면 `completed`로 바꾸고 `PROGRESS.md`에 기록한다.
