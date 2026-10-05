# Git / PR 워크플로우 규칙

이 문서는 사람 기여자를 위한 안내가 아니라, **이 레포에서 커밋을 만들거나 브랜치를 생성하거나 PR을 작성하는 AI(Claude Code 등)가 해당 작업을 수행하기 전에 반드시 따라야 할 규칙**이다.

커밋 메시지 규칙(Conventional Commits 형식, 타입, 명사형 제목, `-` 단위 본문, 한 커밋 한 논리적 변경, 테스트 통과 상태에서만 커밋, `Claude-Session` 트레일러 금지)은 전역 `~/.claude/CLAUDE.md`를 따른다. 이 문서는 이 프로젝트에서만 적용되는 규칙을 정한다.

## 브랜치

- `main` → 보호된 브랜치. 모든 브랜치는 여기로 PR을 보낸다. `main`에 직접 push하지 않는다.
- 브랜치 이름은 커밋 타입과 맞춘다: `feat/[domain]-[feature]`, `fix/[description]`, `refactor/[description]`, `test/[description]`, `docs/[description]`, `chore/[description]`, `perf/[description]`
  - 예: `feat/reservation-conditional-update`, `chore/add-konsist`

## 이슈

- 이슈는 템플릿(`feature`, `bug`, `refactor`, `chore`)으로 만든다. 빈 이슈는 허용하지 않는다.
- 기능, 버그 수정, 리팩토링, 구조에 영향을 주는 빌드·설정 변경처럼 이슈가 필요한 작업은 PR을 올릴 때 이슈도 함께 만들어 PR 본문의 "연관된 이슈"에 `- Closes #이슈번호` 형식(목록 항목)으로 연결한다. 이슈가 없으면 `- 없음 (사유)`로 적는다. 진행 기록 갱신 같은 사소한 문서 변경은 이슈 없이 올려도 된다.
- 이슈와 PR의 Assignees에는 `jinwoojwa`를 지정한다.
- 이슈의 "작업 상세 내용" 체크박스는 PR을 열기 전에 이번 PR에서 완료한 항목을 `gh issue edit`로 `- [x]`로 체크한다. 이슈를 닫아도 체크박스는 자동으로 갱신되지 않는다.

## PR

- PR 제목은 커밋 제목 규칙(타입, 명사형, 끝에 구두점 없음)을 따른다.
- PR을 열기 전에 관련 커밋들이 커밋 규칙을 따르는지 확인하고 `./scripts/verify.sh`를 통과시킨다.
- PR 본문에도 `Claude-Session` 트레일러/링크를 포함하지 않는다.
- PR 템플릿의 "영향 범위"(DB 마이그레이션, API·이벤트 계약, 다른 서비스 영향)를 반드시 채운다.

## 머지 방식

- **Rebase and merge**만 사용한다. PR의 논리 커밋이 `main`에 그대로 쌓이므로, 작업 중에도 커밋 규칙을 지켜 커밋한다.
- 머지 후 브랜치는 자동으로 삭제된다.
