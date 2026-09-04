#!/usr/bin/env bash
set -Eeuo pipefail

tag="${1:-}"
commit="${2:-}"
issue_number="${3:-}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+-uat\.[0-9]+$ ]] || { printf 'invalid UAT tag\n' >&2; exit 2; }
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || { printf 'invalid commit\n' >&2; exit 2; }
[[ "$issue_number" =~ ^[0-9]+$ ]] || { printf 'invalid issue number\n' >&2; exit 2; }

issue="$(gh api "repos/$GITHUB_REPOSITORY/issues/$issue_number")"
[[ "$(jq -r '.state' <<<"$issue")" == "closed" ]] || { printf 'acceptance issue is not closed\n' >&2; exit 1; }
jq -e '.labels | any(.name == "uat-accepted")' <<<"$issue" >/dev/null || {
  printf 'acceptance issue lacks uat-accepted label\n' >&2
  exit 1
}
body="$(jq -r '.body // ""' <<<"$issue")"
grep -Fq '<!-- joysong-acceptance-schema:1 -->' <<<"$body" || { printf 'issue checklist schema mismatch\n' >&2; exit 1; }
grep -Fq "<!-- joysong-uat-tag:${tag} -->" <<<"$body" || { printf 'issue tag marker mismatch\n' >&2; exit 1; }
grep -Fq "<!-- joysong-commit:${commit} -->" <<<"$body" || { printf 'issue commit marker mismatch\n' >&2; exit 1; }
manifest_sha="$(sed -n 's/^<!-- joysong-manifest-sha:\([0-9a-f]\{64\}\) -->$/\1/p' <<<"$body")"
[[ "$manifest_sha" =~ ^[0-9a-f]{64}$ ]] || { printf 'issue manifest SHA marker is missing or duplicated\n' >&2; exit 1; }
if grep -Eq '^[[:space:]]*-[[:space:]]+\[[[:space:]]\]' <<<"$body"; then
  printf 'acceptance issue still contains unchecked checklist items\n' >&2
  exit 1
fi
required_checks=(
  '使用既有测试账号完成密码登录'
  '虚构机构、医生、项目和内容正常'
  'AI 对话与翻译正常'
  'OSS 图片上传与读取正常'
  '用户手动点击后模拟支付立即成功'
  '用户提交全额退款，管理员批准后模拟退款成功'
  '使用上一 UAT 版本覆盖安装 APK，数据保留'
)
for check in "${required_checks[@]}"; do
  count="$({ grep -Fxc -- "- [x] $check" <<<"$body" || true; })"
  uppercase_count="$({ grep -Fxc -- "- [X] $check" <<<"$body" || true; })"
  ((count + uppercase_count == 1)) || {
    printf 'acceptance issue lacks exactly one completed required item: %s\n' "$check" >&2
    exit 1
  }
done
completed_count="$(grep -Ec '^[[:space:]]*-[[:space:]]+\[[xX]\][[:space:]]+' <<<"$body" || true)"
[[ "$completed_count" == "${#required_checks[@]}" ]] || {
  printf 'acceptance issue checklist count is invalid\n' >&2
  exit 1
}

release="$(gh api "repos/$GITHUB_REPOSITORY/releases/tags/$tag")"
[[ "$(jq -r '.draft' <<<"$release")" == "true" || "$(jq -r '.prerelease' <<<"$release")" == "true" ]] || {
  printf 'UAT release is neither draft nor prerelease\n' >&2
  exit 1
}
release_dir="$(mktemp -d)"
cleanup() { rm -rf --one-file-system "$release_dir"; }
trap cleanup EXIT
gh release download "$tag" --dir "$release_dir"
printf '%s  %s\n' "$manifest_sha" "$release_dir/manifest.json" | sha256sum --check --status || {
  printf 'current release manifest differs from the accepted manifest\n' >&2
  exit 1
}
python3 - "$release_dir" "$tag" "$commit" <<'PY'
import hashlib
import json
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
tag, commit = sys.argv[2:]
manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
if manifest.get("schemaVersion") != 1 or manifest.get("environment") != "uat":
    raise SystemExit("release manifest identity is invalid")
if manifest.get("tag") != tag or manifest.get("commit") != commit:
    raise SystemExit("release manifest tag or commit mismatch")
if not isinstance(manifest.get("buildNumber"), int) or manifest["buildNumber"] < 1:
    raise SystemExit("release buildNumber is invalid")
artifacts = manifest.get("artifacts")
if not isinstance(artifacts, list) or not artifacts:
    raise SystemExit("release artifact list is invalid")
seen = set()
for artifact in artifacts:
    if not isinstance(artifact, dict):
        raise SystemExit("release artifact entry is invalid")
    name = artifact.get("name")
    expected = artifact.get("sha256")
    if not isinstance(name, str) or pathlib.PurePosixPath(name).name != name or name in seen:
        raise SystemExit("release artifact name is unsafe or duplicated")
    if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise SystemExit("release artifact SHA is invalid")
    seen.add(name)
    path = root / name
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise SystemExit(f"release artifact differs from accepted manifest: {name}")
PY
printf 'UAT acceptance verified: tag=%s commit=%s issue=%s manifest=%s\n' \
  "$tag" "$commit" "$issue_number" "$manifest_sha"
