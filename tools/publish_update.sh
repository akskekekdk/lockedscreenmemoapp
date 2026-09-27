#!/usr/bin/env bash
# 새 버전을 빌드해서 앱 안 업데이트용 memo-releases 브랜치에 올린다.
#   tools/publish_update.sh "이번 업데이트 내용"
# 앱은 https://raw.githubusercontent.com/<저장소>/memo-releases/update.json 을 읽어 새 버전을 알아챈다.
# 서명 키(signing/memo.keystore 또는 MEMO_KEYSTORE)가 설치된 앱과 같아야 덮어 설치된다.
set -euo pipefail

REPO="akskekekdk/lockedscreenmemoapp"
BRANCH="memo-releases"
NOTES="${1:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ ! -f "${MEMO_KEYSTORE:-signing/memo.keystore}" ]]; then
  echo "서명 키가 없습니다: ${MEMO_KEYSTORE:-signing/memo.keystore}" >&2
  exit 1
fi

./gradlew assembleRelease -q
META="app/build/outputs/apk/release/output-metadata.json"
CODE=$(python3 -c "import json;print(json.load(open('$META'))['elements'][0]['versionCode'])")
NAME=$(python3 -c "import json;print(json.load(open('$META'))['elements'][0]['versionName'])")
APK="app/build/outputs/apk/release/$(python3 -c "import json;print(json.load(open('$META'))['elements'][0]['outputFile'])")"

WORK="$(mktemp -d)"
trap 'git worktree remove --force "$WORK" >/dev/null 2>&1 || true' EXIT
if git fetch -q origin "$BRANCH" 2>/dev/null; then
  git worktree add -q -B "$BRANCH" "$WORK" "origin/$BRANCH"
else
  git worktree add -q --detach "$WORK"
  git -C "$WORK" checkout -q --orphan "$BRANCH"
fi

# 이 브랜치에는 update.json 과 최신 APK 하나만 둔다(소스 코드 X, 저장소가 커지지 않게)
git -C "$WORK" rm -rq --cached . >/dev/null 2>&1 || true
find "$WORK" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
cp "$APK" "$WORK/memo-$NAME.apk"
python3 - "$WORK/update.json" "$CODE" "$NAME" "https://raw.githubusercontent.com/$REPO/$BRANCH/memo-$NAME.apk" "$NOTES" <<'PY'
import json, sys
path, code, name, url, notes = sys.argv[1:]
json.dump({"versionCode": int(code), "versionName": name, "apk": url, "notes": notes},
          open(path, "w"), ensure_ascii=False, indent=2)
PY

git -C "$WORK" add -A
git -C "$WORK" commit -qm "Release $NAME"
git -C "$WORK" push -q origin "HEAD:$BRANCH"
echo "올림: $NAME (versionCode $CODE)"
