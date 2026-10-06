# ADR-0012: PR은 머지 커밋으로 머지

## 상태

Accepted (2026-10-06). [ADR-0008](0008-repository-conventions.md)의 "Rebase and merge만 사용, 선형 이력" 결정을 대체한다.

## 배경 (Context)

초기에는 PR의 커밋이 `main`에 그대로 쌓이도록 Rebase and merge만 허용하고 선형 이력을 강제했다.
이 방식은 머지할 때 커밋 해시가 바뀌어 PR 브랜치의 커밋과 `main`의 커밋이 서로 다른 객체가 되고, PR 단위의 경계가 이력에 남지 않는다.

## 결정 (Decision)

- PR은 **Merge commit**으로만 머지한다. Rebase and merge와 Squash and merge는 끈다.
- `main` 브랜치 보호에서 "선형 이력 필수"를 끈다(GitHub는 선형 이력이 켜져 있으면 머지 커밋을 허용하지 않는다). PR·CI(`verify`) 필수, 승인 0, force push·삭제 금지, 관리자 포함은 그대로다.
- 머지 커밋 제목은 PR 제목, 본문은 비운다. PR 안의 커밋 규칙(Conventional Commits, 한 커밋 한 논리적 변경)은 그대로 지킨다.
- 브랜치를 최신으로 맞출 때는 `main`을 브랜치로 머지한다.

## 결과 (Consequences)

- 얻는 것: PR 브랜치의 커밋이 그대로 보존되고(해시 유지) 머지 커밋이 PR 단위의 경계가 되어 PR 단위로 되돌리거나 추적하기 쉽다.
- 감수하는 것: `main` 이력이 선형이 아니라 `git log`에 머지 커밋이 섞인다. 이력을 한 줄로 보려면 `--first-parent` 등을 써야 한다. 이미 `main`에 들어간 이전 PR(#1~#19)은 Rebase로 머지된 채 남는다.
