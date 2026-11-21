#!/usr/bin/env bash
# PR 요약에 필요한 재료를 한 번에 모아 출력한다.
#   사용: collect.sh [base-branch]
# 출력: 기준 브랜치, 커밋 목록(전체 메시지), 변경 파일 통계, 코드 diff(바이너리·생성물 제외, 길면 잘림), 기존 PR 정보
set -uo pipefail

# gh: 이 PC에서는 PATH 의 'gh' 가 다른 도구일 수 있어 진짜 GitHub CLI 를 찾는다
GH=""
for c in gh "$HOME/.local/bin/gh"; do
  if "$c" --version 2>/dev/null | grep -q '^gh version'; then GH="$c"; break; fi
done

BASE="${1:-}"
if [ -z "$BASE" ]; then
  if [ -n "$GH" ]; then BASE=$("$GH" repo view --json defaultBranchRef -q .defaultBranchRef.name 2>/dev/null); fi
  [ -z "$BASE" ] && BASE=$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null | sed 's#^origin/##')
  [ -z "$BASE" ] && BASE=main
fi
HEAD_BRANCH=$(git rev-parse --abbrev-ref HEAD)

git fetch --quiet origin "$BASE" 2>/dev/null
BASE_REF="origin/$BASE"
git rev-parse --verify --quiet "$BASE_REF" >/dev/null || BASE_REF="$BASE"
MB=$(git merge-base "$BASE_REF" HEAD)

echo "=== BRANCH ==="
echo "head: $HEAD_BRANCH"
echo "base: $BASE (merge-base ${MB:0:7})"
echo "gh: ${GH:-not found}"
echo "uncommitted changes: $(git status --porcelain | wc -l) files (커밋 안 된 변경은 PR 에 포함되지 않음)"

echo
echo "=== COMMITS (oldest first) ==="
git log --reverse --format='--- %h %ad %an%n%B' --date=short "$MB"..HEAD

echo
echo "=== FILES CHANGED ==="
git diff --stat=120 "$MB"..HEAD | tail -n 80

echo
echo "=== DIFF (code only; images/binaries/wrapper/lock files excluded; truncated at 1500 lines) ==="
git diff "$MB"..HEAD -- . \
  ':(exclude)*.png' ':(exclude)*.jpg' ':(exclude)*.webp' ':(exclude)*.jar' \
  ':(exclude)gradlew' ':(exclude)gradlew.bat' ':(exclude)*.lock' ':(exclude)package-lock.json' \
  | head -n 1500
TOTAL=$(git diff "$MB"..HEAD | wc -l)
[ "$TOTAL" -gt 1500 ] && echo "... (diff 전체 ${TOTAL}줄 중 일부만 표시. 필요한 파일은 git diff $MB..HEAD -- <path> 로 더 보기)"

echo
echo "=== EXISTING PR ==="
if [ -n "$GH" ]; then
  "$GH" pr view "$HEAD_BRANCH" --json number,title,url,state,baseRefName -q '"#\(.number) [\(.state)] \(.title) -> \(.baseRefName)\n\(.url)"' 2>/dev/null || echo "none"
else
  echo "unknown (gh 없음)"
fi
