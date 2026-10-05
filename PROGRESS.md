# PROGRESS

## 현재 상태

- 설계 확정: 서비스 경계, 핵심 원칙, 시나리오 결정, 기술 결정(ADR-0001~0007), ERD(테이블 13개)가 확정됐다. 구현 기준 원본은 이 레포의 `docs/`이다.
- 저장소 골격이 만들어졌다(빌드·테스트·린트 통과). 도메인 코드는 아직 없다.
- 첫 push를 마쳤고 CI가 통과했다(F-001 완료). Actions 버전 갱신 PR(#1)을 머지했고, Kotlin·Spring Boot의 마이너·메이저 업데이트는 Dependabot에서 제외했다(WMS와 버전 정렬).
- Flyway 마이그레이션(F-003)을 작성했다: V1 테이블 13개, V2 외래키. 핵심 제약은 `SchemaConstraintTest`가 검증한다.
- GitHub 저장소 설정(F-002)을 적용했다: 라벨 4개, Rebase만 허용·머지 후 브랜치 삭제, `main` 보호(PR과 CI `verify` 필수, force push·삭제 금지, 선형 이력, 승인 0, 관리자에게도 적용).
- 미확정: `reservation.channel` 값(채널 종류), 캐시 도입 시점, `dozy-auth` 쪽 `ims` audience·role·system client 등록.

## 세션 로그

### 2026-10-05

- Notion에서 시나리오, 핵심 원칙, 기술 결정, ERD를 확정하고 `docs/`로 이식했다.
- Gradle(Kotlin DSL) 프로젝트, 컨텍스트 로드 테스트(Testcontainers MySQL, Flyway), Spotless(ktlint)를 추가했다.
- `docker-compose.yml`(MySQL, 비밀번호는 `.env` 필수), `scripts/verify.sh`, CI(GitHub Actions), Dependabot을 추가했다.
- 이슈 템플릿 4종(bug, feature, refactor, chore)과 PR 템플릿을 추가했다.
- `AGENTS.md`(본문)와 `CLAUDE.md`(`@AGENTS.md`), `docs/`(원칙, 아키텍처, ERD, 동시성·멱등, 컨벤션, 테스트, 시나리오, Git 워크플로우, ADR)를 작성했다.
- `./scripts/verify.sh`와 로컬 실행(compose, bootRun)을 확인했고, 논리 단위 커밋 8개를 `main`에 push했다. CI가 통과했다.
- Dependabot PR #1(Actions)을 머지하고 #3(mockito-kotlin 6.4.0)은 WMS와 맞추려고 닫았다. Kotlin·Spring Boot 마이너·메이저 제외 규칙을 PR #5로 머지했다.
- GitHub 저장소 설정을 적용했다(라벨, 머지 방식, `main` 브랜치 보호).
- F-003: ERD를 Flyway V1(테이블)·V2(외래키)로 옮기고, 멱등 키 `VARCHAR(100) ascii_bin`·Lot 번호 대소문자 구분을 확정했다(ERD-07, ERD-08). 스키마 제약 테스트 15개를 추가하고, 제약을 일부러 제거해 테스트가 실패하는 것을 확인했다.

## 다음 세션에서 할 일

1. 열려 있는 Dependabot PR #2를 확인한다. 제외 규칙이 적용되면 Kotlin 2.4.20 변경이 빠지고 다시 생성된다. 제외가 적용되지 않았으면 규칙을 고친다.
2. 전역 공통 모듈(F-004): BaseEntity·감사, 오류 응답 규약.
