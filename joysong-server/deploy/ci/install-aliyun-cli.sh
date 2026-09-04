#!/usr/bin/env bash
set -Eeuo pipefail

readonly VERSION=3.4.11
readonly ARCHIVE="aliyun-cli-linux-${VERSION}-amd64.tgz"
readonly SHA256=a7e3df497db14c10d4d7587795e9fa7849b0c51dfce02908b9de5a41fe717d5c
readonly URL="https://github.com/aliyun/aliyun-cli/releases/download/v${VERSION}/${ARCHIVE}"
readonly OSSUTIL_VERSION=2.4.0
readonly OSSUTIL_ARCHIVE="ossutil-${OSSUTIL_VERSION}-linux-amd64.zip"
readonly OSSUTIL_SHA256=85edf66b2fb7238f5c7e25cab820cf29312319fe4935b7c86a6b8485eb434f3c
readonly OSSUTIL_URL="https://gosspublic.alicdn.com/ossutil/v2/${OSSUTIL_VERSION}/${OSSUTIL_ARCHIVE}"

: "${RUNNER_TEMP:?RUNNER_TEMP is required}"
: "${GITHUB_PATH:?GITHUB_PATH is required}"
command -v unzip >/dev/null || { printf 'unzip is required\n' >&2; exit 2; }

download_dir="$(mktemp -d "$RUNNER_TEMP/aliyun-cli.XXXXXX")"
trap 'rm -rf "$download_dir"' EXIT
curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
  --retry 3 --retry-all-errors --output "$download_dir/$ARCHIVE" "$URL"
printf '%s  %s\n' "$SHA256" "$download_dir/$ARCHIVE" | sha256sum --check --status
tar -tzf "$download_dir/$ARCHIVE" | grep -Eq '(^|/)aliyun$'
tar -xzf "$download_dir/$ARCHIVE" -C "$download_dir"

install_dir="$RUNNER_TEMP/aliyun-cli-bin"
mkdir -p "$install_dir"
aliyun_binary="$(find "$download_dir" -type f -name aliyun -print -quit)"
[[ -n "$aliyun_binary" ]] || { printf 'aliyun binary not found\n' >&2; exit 2; }
install -m 0755 "$aliyun_binary" "$install_dir/aliyun"
printf '%s\n' "$install_dir" >>"$GITHUB_PATH"
"$install_dir/aliyun" version

curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
  --retry 3 --retry-all-errors --output "$download_dir/$OSSUTIL_ARCHIVE" "$OSSUTIL_URL"
printf '%s  %s\n' "$OSSUTIL_SHA256" "$download_dir/$OSSUTIL_ARCHIVE" | sha256sum --check --status
unzip -q "$download_dir/$OSSUTIL_ARCHIVE" -d "$download_dir/ossutil"
ossutil_binary="$(find "$download_dir/ossutil" -type f -name ossutil -print -quit)"
[[ -n "$ossutil_binary" ]] || { printf 'ossutil binary not found\n' >&2; exit 2; }
install -m 0755 "$ossutil_binary" "$install_dir/ossutil"
"$install_dir/ossutil" version
