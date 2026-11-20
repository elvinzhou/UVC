#!/usr/bin/env bash
# Prints the next version (without "v") derived from Conventional Commit subjects since the
# latest v* tag, or prints nothing when no commit warrants a release.
#
#   feat:                      -> minor
#   fix: / perf: / revert:     -> patch
#   type!: or "BREAKING CHANGE" in the body -> major (minor while the major version is 0)
#   anything else (docs, chore, ci, test, build, style, refactor, ...) -> no release
set -euo pipefail

last=$(git describe --tags --abbrev=0 --match 'v[0-9]*' 2>/dev/null || true)
if [ -n "$last" ]; then
  range="$last..HEAD"; base=${last#v}
else
  range="HEAD"; base="0.0.0"
fi

bump=0 # 0 none, 1 patch, 2 minor, 3 major
re='^([a-z]+)(\([^)]*\))?(!)?:[[:space:]]'
for sha in $(git rev-list "$range"); do
  subject=$(git log -1 --format=%s "$sha")
  body=$(git log -1 --format=%b "$sha")
  level=0
  if [[ $subject =~ $re ]]; then
    type=${BASH_REMATCH[1]}
    case $type in
      feat) level=2 ;;
      fix|perf|revert) level=1 ;;
    esac
    [[ -n ${BASH_REMATCH[3]} ]] && level=3
  fi
  grep -q '^BREAKING[ -]CHANGE' <<<"$body" && level=3
  (( level > bump )) && bump=$level
done

(( bump == 0 )) && exit 0

IFS=. read -r major minor patch <<<"$base"
if [ -z "$last" ]; then
  # First release: nothing to increment from.
  echo "0.1.0"; exit 0
fi
if (( bump == 3 && major == 0 )); then bump=2; fi
case $bump in
  3) major=$((major + 1)); minor=0; patch=0 ;;
  2) minor=$((minor + 1)); patch=0 ;;
  1) patch=$((patch + 1)) ;;
esac
echo "$major.$minor.$patch"
