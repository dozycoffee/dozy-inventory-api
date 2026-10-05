# ADR-0008: 저장소 운영 규칙

## 상태

Accepted (2026-10-05)

## 배경 (Context)

저장소를 초기화하면서 빌드, 로컬 환경, CI, 템플릿, 문서의 운영 규칙을 정했다. WMS의 구성을 기준으로 삼되 중복과 하드코딩을 정리했다.

## 결정 (Decision)

| 영역 | 결정 |
|------|------|
| 빌드 | Gradle Kotlin DSL, 패키지 루트 `com.dozycoffee.ims`. 초기 의존성은 골격에 필요한 최소(인증·Kafka는 해당 기능 구현 시 추가) |
| 로컬 환경 | `docker-compose.yml`은 MySQL만. DB 비밀번호는 `.env`의 필수 변수(기본값 없음), 호스트 포트 DB 3307·앱 8082(WMS와 동시 실행), 수동 `docker compose up -d` |
| 검증 | `scripts/verify.sh`(Spotless 검사 + 빌드 + 테스트)를 로컬과 CI가 공통으로 실행한다 |
| 린트 | ktlint(Spotless). 아키텍처 테스트(Konsist)는 첫 도메인 PR에서 도입한다 |
| CI | GitHub Actions. `setup-gradle` 캐시, 같은 PR의 이전 실행 취소, 실패 시 테스트 리포트 업로드, 수동 실행 |
| 템플릿 | 이슈 `bug`/`feature`/`refactor`/`chore`(Markdown), PR은 테스트·검증과 영향 범위(마이그레이션, API·이벤트 계약) 칸을 둔다 |
| 문서 | `AGENTS.md`가 본문, `CLAUDE.md`는 이를 가져온다. 상세는 `docs/`로 나누고 읽는 시점을 안내한다. 이 레포의 `docs/`가 구현 기준 원본이다 |
| Git | 커밋 규칙은 전역 규칙을 따르고 `docs/git-workflow.md`에는 프로젝트 고유 규칙만 둔다. PR은 Rebase and merge만 사용한다 |
| 의존성 | Dependabot(Gradle, GitHub Actions) 주 1회, 마이너·패치는 묶어서 |
| 브랜치 보호 | `main`: PR·CI 필수, force push·삭제 금지, 선형 이력, 승인 0, Rebase만 허용, 머지 후 브랜치 삭제 (원격 설정으로 적용) |

## 결과 (Consequences)

- 얻는 것: 로컬과 CI의 검증이 같고, 비밀이 레포에 남지 않으며, 결정이 코드와 함께 버전 관리된다.
- 감수하는 것: 레포가 공개라서 설계 문서도 공개된다. `.env` 복사 한 단계가 추가된다. 문서 원본이 레포로 옮겨져 Notion의 초안과 어긋날 수 있다.
