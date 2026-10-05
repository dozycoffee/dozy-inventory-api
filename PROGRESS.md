# PROGRESS

## 현재 상태

- 설계 확정: 서비스 경계, 핵심 원칙, 시나리오 결정, 기술 결정(ADR-0001~0007), ERD(테이블 13개)가 확정됐다. 구현 기준 원본은 이 레포의 `docs/`이다.
- 저장소 골격이 만들어졌다(빌드·테스트·린트 통과). 도메인 코드는 아직 없다.
- 첫 push를 마쳤고 CI가 통과했다(F-001 완료). Dependabot이 Actions 버전 갱신 PR을 열었다(PR #1).
- 미확정: `reservation.channel` 값(채널 종류), 캐시 도입 시점, `dozy-auth` 쪽 `ims` audience·role·system client 등록.

## 세션 로그

### 2026-10-05

- Notion에서 시나리오, 핵심 원칙, 기술 결정, ERD를 확정하고 `docs/`로 이식했다.
- Gradle(Kotlin DSL) 프로젝트, 컨텍스트 로드 테스트(Testcontainers MySQL, Flyway), Spotless(ktlint)를 추가했다.
- `docker-compose.yml`(MySQL, 비밀번호는 `.env` 필수), `scripts/verify.sh`, CI(GitHub Actions), Dependabot을 추가했다.
- 이슈 템플릿 4종(bug, feature, refactor, chore)과 PR 템플릿을 추가했다.
- `AGENTS.md`(본문)와 `CLAUDE.md`(`@AGENTS.md`), `docs/`(원칙, 아키텍처, ERD, 동시성·멱등, 컨벤션, 테스트, 시나리오, Git 워크플로우, ADR)를 작성했다.
- `./scripts/verify.sh`와 로컬 실행(compose, bootRun)을 확인했고, 논리 단위 커밋 8개를 `main`에 push했다. CI가 통과했다.

## 다음 세션에서 할 일

1. Dependabot의 Actions 버전 갱신 PR(#1)을 검토한다. CI에서 Node 20·`setup-java` v4 deprecated 경고가 나왔고 이 PR이 해결한다.
2. 사용자 확인 후 GitHub 저장소 설정(라벨, 브랜치 보호, 머지 설정)을 적용한다(F-002).
3. `docs/erd.md`를 Flyway `V1` 마이그레이션으로 옮긴다(F-003).
