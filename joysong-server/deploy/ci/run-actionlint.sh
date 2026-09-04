#!/usr/bin/env bash
set -Eeuo pipefail

readonly VERSION=1.7.12
readonly ARCHIVE="actionlint_${VERSION}_linux_amd64.tar.gz"
readonly SHA256=8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8
readonly URL="https://github.com/rhysd/actionlint/releases/download/v${VERSION}/${ARCHIVE}"

temporary_root="$(mktemp -d "${RUNNER_TEMP:-/tmp}/actionlint.XXXXXX")"
trap 'rm -rf "$temporary_root"' EXIT
curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
  --retry 3 --retry-all-errors --output "$temporary_root/$ARCHIVE" "$URL"
printf '%s  %s\n' "$SHA256" "$temporary_root/$ARCHIVE" | sha256sum --check --status
tar -xzf "$temporary_root/$ARCHIVE" -C "$temporary_root" actionlint
"$temporary_root/actionlint" -color
