# PROGRESS

## 현재 상태

- 설계 확정: 서비스 경계, 핵심 원칙, 시나리오 결정, 기술 결정(ADR-0001~0009), ERD(테이블 13개)가 확정됐다. 구현 기준 원본은 이 레포의 `docs/`이다.
- 완료: F-001(골격), F-002(GitHub 저장소 설정), F-003(Flyway 마이그레이션 V1·V2), F-004(전역 공통 모듈).
- 패키지 구조 확정(ADR-0010): 도메인 모델과 영속성 엔티티 분리(도메인은 감사 필드를 모름), 다른 도메인은 UseCase로만 호출, 도메인 패키지 6개, `/api/v1`. F-005(상품 마스터)는 사용자가 직접 구현한다.
- `main`은 보호되어 있다(PR과 CI `verify` 필수, Rebase and merge만 허용). Kotlin·Spring Boot·Gradle wrapper의 마이너·메이저 업데이트는 Dependabot에서 제외했다(WMS와 버전 정렬).
- 로드맵 변경: 인증 연동(F-020)을 첫 API(F-008) 앞으로 옮겼다. `dozy-auth` 서버에서 `ims` audience, `ims:service`·`ims:admin` role, system client를 등록하는 것은 개발·테스트의 선행 조건이 아니다(테스트는 `auth-test`로 작성). 실제 서버와 연동을 확인하거나 배포하기 전에 관리자 API로 등록한다.
- 미확정: `reservation.channel` 값(채널 종류), 캐시 도입 시점, system 토큰에 클라이언트 이름이 없어 `inventory_history.requester_service`를 채우는 방식(F-020에서 결정).

## 세션 로그

### 2026-10-05

- Notion에서 시나리오, 핵심 원칙, 기술 결정, ERD를 확정하고 `docs/`로 이식했다.
- Gradle(Kotlin DSL) 프로젝트, 컨텍스트 로드 테스트, Spotless(ktlint), 로컬 환경(compose, `.env`), `scripts/verify.sh`, CI, Dependabot, 이슈·PR 템플릿, `AGENTS.md`/`CLAUDE.md`, `docs/`를 만들어 `main`에 push했다.
- Dependabot PR #1(Actions)을 머지하고 #3(mockito-kotlin 6.4.0)과 #6(Gradle 9.8.0)은 WMS와 맞추려고 닫았다. Kotlin·Spring Boot·Gradle wrapper 제외 규칙을 추가했다.
- GitHub 저장소 설정을 적용했다(라벨 4개에 아이콘, 머지 방식, `main` 브랜치 보호).
- F-003: ERD를 Flyway V1(테이블)·V2(외래키)로 옮기고 멱등 키 `VARCHAR(100) ascii_bin`·Lot 번호 대소문자 구분을 확정했다(ERD-07, ERD-08). 스키마 제약 테스트 15개를 추가했다.

### 2026-10-06

- F-004: `global` 모듈(오류 응답, `Clock`, 감사, `Actor`, 중복 키 변환)을 구현했다. 결정은 ADR-0009: `Clock` 주입, `IMS_` 오류 코드 접두사, `local` 프로필 전용 `Actor`(그 외 프로필은 기동 실패), Spring Data Auditing.
- `Actor`를 Reactor Context로 전달하는 `ActorContext`를 추가했다. 스케줄러는 `ActorContext.with(SystemActor)`로 감싸 실행한다.
- 테스트 28개를 추가했다(오류 응답 12, 감사 5, `Actor` 6, 중복 키 3, 프로필 가드 2). 시각·`Actor` 처리를 일부러 고장 내 테스트가 실패하는 것을 확인했다.
- 패키지 구조를 확정하고(ADR-0010) `docs/architecture.md`를 갱신했다. WMS와 달라진 점은 도메인 모델이 `BaseEntity`를 상속하지 않는 것이다.
- `dozy-auth` 최신 상태를 확인했다: audience·role·system client 등록 관리 API가 생겼고, 스타터 0.2.1(WebFlux, system token 클라이언트)이 공개돼 있다. system 토큰에는 클라이언트 이름이 없고 `principalId`(UUID)만 있다.

## 다음 세션에서 할 일

1. 상품 마스터(F-005): 사용자가 `docs/architecture.md` 구조에 맞춰 구현한다. category·status enum과 CHECK 제약의 일치 검증 테스트를 함께 둔다. 구현 중 구조가 어긋나면 architecture.md를 갱신한다.
2. Konsist 아키텍처 테스트 도입(F-006), 재고 모델(F-007).
3. 첫 API(F-008) 전에 인증 연동(F-020)을 진행한다. `auth-test`로 테스트하며, 실제 서버 연동 확인 전에 `dozy-auth`에 `ims` audience·role·system client를 등록한다(사용자 작업).
4. Dependabot이 제외 규칙대로 동작하는지 다음 점검 때 확인한다.
