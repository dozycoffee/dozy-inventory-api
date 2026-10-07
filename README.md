## 목차

1. [프로젝트 소개](#1-프로젝트-소개)
2. [프로젝트 목표](#2-프로젝트-목표)
3. [기술 스택](#3-기술-스택)
4. [ERD](#4-erd)
5. [프로젝트 폴더 구조](#5-프로젝트-폴더-구조)
6. [실행 방법](#6-실행-방법)
7. [문서](#7-문서)

<br>

## 1. 프로젝트 소개

![DOZY COFFEE Banner](.github/assets/dozycoffee_banner.png)

본 프로젝트는 커피 프랜차이즈 **DOZY COFFEE**의 원부자재 재고 관리 서비스(inventory) 백엔드 API 서버입니다.

inventory 서비스는 재고의 단일 진실 공급원(SSOT)으로, "무엇이 몇 개 있고, 그중 몇 개를 약속할 수 있는가"를 책임집니다. 창고 작업(입고·출고·반품·실사·폐기의 물리적 수행)은 WMS가, 재고 수량과 예약은 inventory 서비스가 맡고, OMS와 가맹점 서비스가 같은 재고를 inventory 서비스를 통해 바라봅니다.

> 설계가 확정되어 도메인별로 구현을 진행 중입니다. 구현 순서는 [feature_list.json](feature_list.json)을 따릅니다.

<br>

## 2. 프로젝트 목표

- 여러 채널(OMS, 가맹점)이 같은 재고를 동시에 약속해도 초과 판매가 나지 않는 예약 처리
- 수량·이력·이벤트를 한 트랜잭션으로 저장해 재고와 기록이 어긋나지 않는 원장 설계
- 멱등 키와 Outbox 기반 이벤트 발행으로 재시도·중복에 안전한 서비스 간 연동
- WMS와 분리된 재고 서비스의 경계 설계 (창고 단위 수량, 예약, 조정 확정)

<br>

## 3. 기술 스택

### Backend

| 분류 | 기술 |
|------|------|
| Language | Kotlin 2.3 (JVM 21) |
| Framework | Spring Boot 4.1, Spring WebFlux, Kotlin Coroutines |
| DB Access | Spring Data R2DBC |
| DB / Migration | MySQL 8.0 / Flyway |
| Messaging | Kafka + Outbox (구현 예정) |
| Auth | dozy-auth 스타터 0.2.1 |
| Build | Gradle (Kotlin DSL) |
| Lint | Spotless (ktlint) |
| Test | JUnit 5, Mockito-Kotlin, Testcontainers |
| API 문서 | Spring REST Docs + restdocs-api-spec (OpenAPI 3) |

<br>

## 4. ERD

[docs/erd.md](docs/erd.md)에 ERD 다이어그램과 테이블 정의가 있습니다.

<br>

## 5. 프로젝트 폴더 구조

```
.
├── AGENTS.md / CLAUDE.md                // AI 에이전트·개발자 안내 (CLAUDE.md는 AGENTS.md를 가져옴)
├── docs                                 // 원칙, 아키텍처, ERD, 컨벤션, ADR 등
├── scripts/verify.sh                    // 서식 검사 + 빌드 + 테스트 (로컬·CI 공통)
├── docker-compose.yml                   // 로컬 MySQL
├── feature_list.json / PROGRESS.md      // 작업 목록과 진행 기록
└── src
    ├── main
    │   ├── kotlin/com/dozycoffee/inventory    // 애플리케이션 (도메인별 헥사고날 구조는 docs/architecture.md)
    │   └── resources                    // application.yaml, db/migration (Flyway)
    └── test
        └── kotlin/com/dozycoffee/inventory    // 테스트 (support: Testcontainers 초기화)
```

<br>

## 6. 실행 방법

### 사전 준비

- JDK 21, Docker

### 로컬 실행

```bash
cp .env.example .env        # DB_PASSWORD를 채운다 (SPRING_PROFILES_ACTIVE=local 포함)
docker compose up -d        # MySQL (호스트 포트 3307)
./gradlew bootRun           # 애플리케이션 (포트 8082). local 프로필은 토큰 없이 개발 사용자로 동작한다
```

### 검증

```bash
./scripts/verify.sh         # 서식 검사 + 빌드 + 테스트 (Docker 필요)
./gradlew spotlessApply     # 서식 자동 정렬
```

<br>

## 7. 문서

[docs/README.md](docs/README.md)에서 전체 문서를 확인할 수 있습니다.

- [서비스 경계와 핵심 원칙](docs/principles.md)
- [아키텍처](docs/architecture.md)
- [ERD](docs/erd.md)
- [예약 동시성과 멱등성](docs/concurrency-and-idempotency.md)
- API 명세: 컨트롤러 테스트가 OpenAPI 파일을 만듭니다(`./gradlew openapi3` → `build/api-spec/openapi3.yaml`). CI의 `openapi-spec` 아티팩트에서 받을 수 있습니다 ([ADR-0013](docs/adr/0013-api-docs-with-rest-docs.md))
- [설계 결정 기록(ADR)](docs/adr/README.md)
