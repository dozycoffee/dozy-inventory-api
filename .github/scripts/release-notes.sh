#!/usr/bin/env bash
# 릴리스 노트를 만든다 (release.yml). 결과 Markdown은 표준 출력으로 낸다.
#
#   .github/scripts/release-notes.sh <태그> [커밋]
#
# 직전 v* 태그부터 이 태그까지 main에 병합된 PR을 라벨로 분류해 모은다.
# PR 번호는 병합 커밋 제목 끝의 (#123)에서 읽는다. gh(GH_TOKEN)와 jq가 필요하고, 전체 git 기록이 있어야 한다.
set -euo pipefail

tag="${1:?태그 이름이 필요합니다 (예: v0.1.1)}"
ref="${2:-$tag}"
repo="${GITHUB_REPOSITORY:-dozycoffee/dozy-inventory-api}"

previous="$(git describe --tags --abbrev=0 --match 'v[0-9]*' "${ref}^" 2>/dev/null || true)"
if [[ -n "$previous" ]]; then
  range="${previous}..${ref}"
  compare="https://github.com/${repo}/compare/${previous}...${tag}"
else
  range="$ref"
  compare="https://github.com/${repo}/commits/${tag}"
fi

numbers="$(git log --first-parent --format=%s "$range" | sed -nE 's/.*\(#([0-9]+)\)$/\1/p' | jq -R 'tonumber' | jq -s '.')"

gh pr list --repo "$repo" --state merged --limit 1000 \
  --json number,title,url,author,labels |
  jq -r --argjson numbers "$numbers" --arg compare "$compare" '
    def category:
      [.labels[].name] as $l
      | if any($l[]; test("breaking-change")) then "호환성 변경"
        elif any($l[]; test("feature")) then "기능"
        elif any($l[]; test("bug")) then "버그 수정"
        elif any($l[]; test("security")) then "보안"
        elif any($l[]; test("refactor")) then "리팩토링"
        else "기타" end;
    [ .[]
      | select(.number as $n | $numbers | index($n))
      | select([.labels[].name] | any(test("duplicate|wontfix|release")) | not)
      | . + {category: category} ] as $prs
    | (if ($prs | length) == 0 then "이번 릴리스에는 변경 PR이 없습니다.\n"
       else
         ["호환성 변경", "기능", "버그 수정", "보안", "리팩토링", "기타"]
         | map(. as $c | [$prs[] | select(.category == $c)] | sort_by(.number)
               | select(length > 0)
               | "### \($c)\n\n" + (map("- \(.title) by @\(.author.login) in \(.url)") | join("\n")) + "\n")
         | join("\n")
       end)
    + "\n**Full Changelog**: \($compare)"
  '
