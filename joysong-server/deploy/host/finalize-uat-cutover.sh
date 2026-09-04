#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

usage() {
  printf '%s\n' \
    "usage: $0 <old-service> <old-data-root> <new-nginx-conf> <active-nginx-conf>" >&2
  exit 2
}

systemctl_property_value() {
  local unit="$1" property="$2" line
  line="$(systemctl show -p "$property" "$unit" 2>/dev/null || true)"
  line="${line%%$'\n'*}"
  [[ "$line" == "${property}="* ]] || return 0
  printf '%s' "${line#*=}"
}

[[ "$EUID" -eq 0 ]] || { printf 'run as root\n' >&2; exit 2; }
(($# == 4)) || usage
install -d -o root -g root -m 0755 /run/lock
exec 9>/run/lock/joysong-uat-deploy.lock
flock -n 9 || { printf 'another UAT release or migration operation is active\n' >&2; exit 2; }
old_service="$1"
old_data_root="$2"
new_nginx_config="$3"
active_nginx_config="$4"
readonly baseline_marker=/etc/joysong/uat/BASELINE_CAPTURED
readonly baseline_backup_parent=/var/backups/joysong/uat-bootstrap
readonly data_manifest_helper=/usr/local/lib/joysong/baseline-data-manifest.py

[[ "$old_service" =~ ^[A-Za-z0-9@_.-]+$ ]] || usage
for path_value in "$old_data_root" "$new_nginx_config" "$active_nginx_config"; do
  [[ "$path_value" == /* && "$path_value" != *$'\n'* && "$path_value" != *$'\r'* ]] || usage
done
[[ -d "$old_data_root" && ! -L "$old_data_root" ]] || { printf 'old data root is unavailable or unsafe\n' >&2; exit 2; }
[[ -f "$new_nginx_config" && ! -L "$new_nginx_config" ]] || { printf 'new Nginx config is unavailable or unsafe\n' >&2; exit 2; }
[[ "$active_nginx_config" == /etc/nginx/* && "$active_nginx_config" != *..* ]] || {
  printf 'active Nginx config must be a canonical path below /etc/nginx\n' >&2
  exit 2
}
[[ -f "$active_nginx_config" && ! -L "$active_nginx_config" ]] || {
  printf 'active Nginx config must already be a regular file\n' >&2
  exit 2
}
if [[ ! -f "$baseline_marker" || -L "$baseline_marker" ||
      "$(stat -c '%U:%G:%a' "$baseline_marker" 2>/dev/null || true)" != "root:root:600" ]]; then
  printf 'baseline gate must be a root:root mode 0600 regular non-link file\n' >&2
  exit 2
fi
python3 - "$baseline_marker" <<'PY'
import os
import re
import stat
import sys

path_value = os.fsencode(sys.argv[1])
initial_stat = os.lstat(path_value)
stable_fields = (
    "st_dev", "st_ino", "st_mode", "st_nlink", "st_uid", "st_gid",
    "st_size", "st_mtime_ns", "st_ctime_ns",
)
if (not stat.S_ISREG(initial_stat.st_mode) or stat.S_ISLNK(initial_stat.st_mode) or
        initial_stat.st_nlink != 1 or initial_stat.st_uid != 0 or initial_stat.st_gid != 0 or
        stat.S_IMODE(initial_stat.st_mode) != 0o600):
    raise SystemExit("baseline gate ownership or mode is unsafe")
descriptor = os.open(path_value, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
try:
    opened_stat = os.fstat(descriptor)
    if any(getattr(initial_stat, field) != getattr(opened_stat, field) for field in stable_fields):
        raise SystemExit("baseline gate changed before reading")
    chunks = []
    while True:
        chunk = os.read(descriptor, 1024 * 1024)
        if not chunk:
            break
        chunks.append(chunk)
    final_stat = os.fstat(descriptor)
    if any(getattr(opened_stat, field) != getattr(final_stat, field) for field in stable_fields):
        raise SystemExit("baseline gate changed while reading")
finally:
    os.close(descriptor)
content = b"".join(chunks)
if b"\r" in content:
    raise SystemExit("baseline gate contains a carriage return")
seen = set()
for line in content.split(b"\n"):
    if not line:
        continue
    if b"=" not in line:
        raise SystemExit("baseline gate contains an unsupported line")
    key, value = line.split(b"=", 1)
    if re.fullmatch(br"[A-Z][A-Z0-9_]*", key) is None or not value:
        raise SystemExit("baseline gate contains an unsafe key or empty value")
    if key in seen:
        raise SystemExit("baseline gate contains a duplicate key")
    seen.add(key)
if b"BASELINE_CAPTURED" not in seen:
    raise SystemExit("baseline gate lacks BASELINE_CAPTURED")
PY
if ! grep -Eq '^BASELINE_CAPTURED=true$' "$baseline_marker"; then
  printf 'successful UAT baseline capture is required\n' >&2
  exit 2
fi
[[ ! -e /etc/joysong/uat/CUTOVER_COMPLETED ]] || {
  printf 'UAT final cutover was already completed\n' >&2
    exit 2
  }
read_baseline_value() {
  local key="$1" count value
  count="$(grep -Ec "^${key}=" "$baseline_marker" || true)"
  [[ "$count" == "1" ]] || { printf 'baseline gate must contain exactly one %s\n' "$key" >&2; exit 2; }
  value="$(sed -n "s/^${key}=//p" "$baseline_marker")"
  [[ -n "$value" && "$value" != *$'\r'* && "$value" != *$'\n'* ]] || {
    printf 'baseline gate contains unsafe %s\n' "$key" >&2
    exit 2
  }
  printf '%s' "$value"
}

baseline_database_identity="$(read_baseline_value DATABASE_IDENTITY_SHA256)"
[[ "$baseline_database_identity" =~ ^[0-9a-f]{64}$ ]] || {
  printf 'baseline database identity is missing or invalid\n' >&2
  exit 2
}
baseline_tag="$(read_baseline_value BASELINE_TAG)"
baseline_old_service="$(read_baseline_value OLD_SERVICE)"
baseline_old_jar="$(read_baseline_value OLD_JAR)"
baseline_old_jar_sha256="$(read_baseline_value OLD_JAR_SHA256)"
baseline_old_env="$(read_baseline_value OLD_ENV)"
baseline_old_data_root="$(read_baseline_value OLD_DATA_ROOT)"
baseline_backup_root="$(read_baseline_value BACKUP_ROOT)"
baseline_data_manifest_path="$(read_baseline_value DATA_MANIFEST_PATH)"
baseline_data_manifest_sha256="$(read_baseline_value DATA_MANIFEST_SHA256)"
baseline_old_env_ignored_line_count="$(read_baseline_value OLD_ENV_IGNORED_LINE_COUNT)"
baseline_old_env_ignored_line_sha256="$(read_baseline_value OLD_ENV_IGNORED_LINE_SHA256)"
[[ "$baseline_tag" == "v0.0.0-uat.0" ]] || { printf 'unexpected baseline release tag\n' >&2; exit 2; }
[[ -d "$baseline_backup_parent" && ! -L "$baseline_backup_parent" &&
   "$(readlink -f -- "$baseline_backup_parent")" == "$baseline_backup_parent" ]] || {
  printf 'canonical baseline backup parent is unavailable\n' >&2
  exit 2
}
[[ "$baseline_backup_root" == "$baseline_backup_parent/"* &&
   "$(dirname -- "$baseline_backup_root")" == "$baseline_backup_parent" &&
   -n "$(basename -- "$baseline_backup_root")" &&
   "$(basename -- "$baseline_backup_root")" != "." &&
   "$(basename -- "$baseline_backup_root")" != ".." ]] || {
  printf 'baseline BACKUP_ROOT must be one direct child of the canonical backup parent\n' >&2
  exit 2
}
[[ -d "$baseline_backup_root" && ! -L "$baseline_backup_root" &&
   "$(readlink -f -- "$baseline_backup_root")" == "$baseline_backup_root" &&
   "$(stat -c '%U:%G:%a' "$baseline_backup_root")" == "root:root:700" ]] || {
  printf 'baseline BACKUP_ROOT is unavailable, non-canonical or not root-only\n' >&2
  exit 2
}
expected_data_manifest_path="$baseline_backup_root/DATA_MANIFEST.jsonl"
[[ "$baseline_data_manifest_path" == "$expected_data_manifest_path" &&
   "$baseline_data_manifest_sha256" =~ ^[0-9a-f]{64}$ ]] || {
  printf 'baseline data manifest marker binding is invalid\n' >&2
  exit 2
}
[[ -f "$data_manifest_helper" && ! -L "$data_manifest_helper" && -x "$data_manifest_helper" &&
   "$(stat -c '%U:%G:%a' "$data_manifest_helper")" == "root:root:755" ]] || {
  printf 'root-owned baseline data manifest helper is unavailable\n' >&2
  exit 2
}
[[ -f "$baseline_data_manifest_path" && ! -L "$baseline_data_manifest_path" &&
   "$(stat -c '%U:%G:%a' "$baseline_data_manifest_path")" == "root:root:600" ]] || {
  printf 'baseline data manifest is not a root-only regular file\n' >&2
  exit 2
}
[[ "$(sha256sum "$baseline_data_manifest_path" 2>/dev/null | awk '{ print $1 }')" == \
   "$baseline_data_manifest_sha256" ]] || {
  printf 'baseline data manifest SHA-256 no longer matches its marker binding\n' >&2
  exit 2
}

baseline_checksum_file="$baseline_backup_root/SHA256SUMS"
baseline_checksum_names=(
  old-server.jar
  old-admin.tar.gz
  database.sql.gz
  source-database-BACKUP_COMPLETE
  source-database-SHA256SUMS
  old-joysong.env
  new-joysong.env
  nginx-config.tar.gz
  old-service.txt
  old-service-status.txt
  nginx.txt
  data-migration.txt
  DATA_MANIFEST.jsonl
)
python3 - "$baseline_backup_root" "$baseline_checksum_file" "${baseline_checksum_names[@]}" <<'PY'
import hashlib
import os
import re
import stat
import sys

backup_root = os.fsencode(sys.argv[1])
checksum_path = os.fsencode(sys.argv[2])
expected_names = [os.fsencode(value) for value in sys.argv[3:]]
if len(expected_names) != 13:
    raise SystemExit("baseline checksum contract must contain exactly 13 files")
stable_fields = (
    "st_dev", "st_ino", "st_mode", "st_nlink", "st_uid", "st_gid",
    "st_size", "st_mtime_ns", "st_ctime_ns",
)


def read_root_only(path_value, label, capture_content=False):
    initial_stat = os.lstat(path_value)
    if (not stat.S_ISREG(initial_stat.st_mode) or stat.S_ISLNK(initial_stat.st_mode) or
            initial_stat.st_nlink != 1 or initial_stat.st_uid != 0 or initial_stat.st_gid != 0 or
            stat.S_IMODE(initial_stat.st_mode) != 0o600):
        raise SystemExit("{} must be a root:root mode 0600 regular non-link file".format(label))
    descriptor = os.open(path_value, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        opened_stat = os.fstat(descriptor)
        if any(getattr(initial_stat, field) != getattr(opened_stat, field) for field in stable_fields):
            raise SystemExit("{} changed before reading".format(label))
        value = hashlib.sha256()
        chunks = [] if capture_content else None
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            value.update(chunk)
            if capture_content:
                chunks.append(chunk)
        final_stat = os.fstat(descriptor)
        if any(getattr(opened_stat, field) != getattr(final_stat, field) for field in stable_fields):
            raise SystemExit("{} changed while reading".format(label))
    finally:
        os.close(descriptor)
    content = b"".join(chunks) if capture_content else None
    return content, value.hexdigest().encode("ascii")


checksum_content, _ = read_root_only(checksum_path, "baseline SHA256SUMS", capture_content=True)
checksum_lines = checksum_content.splitlines()
if len(checksum_lines) != 13:
    raise SystemExit("baseline SHA256SUMS must contain exactly 13 entries")
actual_names = []
expected_digests = []
for line in checksum_lines:
    match = re.fullmatch(br"([0-9a-f]{64})  ([A-Za-z0-9._-]+)", line)
    if match is None:
        raise SystemExit("baseline SHA256SUMS contains an unsupported line")
    expected_digests.append(match.group(1))
    actual_names.append(match.group(2))
if actual_names != expected_names:
    raise SystemExit("baseline SHA256SUMS does not contain the exact approved 13-file set")
for name, expected_digest in zip(actual_names, expected_digests):
    _, actual_digest = read_root_only(os.path.join(backup_root, name), os.fsdecode(name))
    if actual_digest != expected_digest:
        raise SystemExit("baseline SHA256SUMS verification failed for {}".format(os.fsdecode(name)))
PY
"$data_manifest_helper" verify "$baseline_backup_root/data" "$baseline_data_manifest_path"

[[ "$baseline_old_env_ignored_line_count" =~ ^[0-9]+$ &&
   "$baseline_old_env_ignored_line_sha256" =~ ^[0-9a-f]{64}$ ]] || {
  printf 'baseline legacy environment audit binding is invalid\n' >&2
  exit 2
}
old_data_root="$(readlink -f -- "$old_data_root")"
baseline_old_env="$(readlink -f -- "$baseline_old_env")"
baseline_old_jar="$(readlink -f -- "$baseline_old_jar")"
baseline_old_data_root="$(readlink -f -- "$baseline_old_data_root")"
[[ "$old_service" == "$baseline_old_service" ]] || {
  printf 'old service does not match the captured baseline\n' >&2
  exit 2
}
[[ "$old_data_root" == "$baseline_old_data_root" ]] || {
  printf 'old data root does not match the captured baseline\n' >&2
  exit 2
}
[[ -f "$baseline_old_env" && ! -L "$baseline_old_env" ]] || {
  printf 'captured old environment file is unavailable or unsafe\n' >&2
  exit 2
}
[[ -f "$baseline_old_jar" && ! -L "$baseline_old_jar" ]] || {
  printf 'captured old JAR is unavailable or unsafe\n' >&2
  exit 2
}
[[ "$baseline_old_jar_sha256" =~ ^[0-9a-f]{64}$ &&
   "$(sha256sum "$baseline_old_jar" | awk '{ print $1 }')" == "$baseline_old_jar_sha256" &&
   "$(sha256sum "/opt/joysong/uat/releases/$baseline_tag/server.jar" | awk '{ print $1 }')" == "$baseline_old_jar_sha256" ]] || {
  printf 'captured old JAR bytes changed or no longer match the managed baseline\n' >&2
  exit 2
}
systemctl is-active --quiet "$old_service" || { printf 'old service must be active before cutover\n' >&2; exit 2; }
if systemctl is-active --quiet joysong@uat.service || systemctl is-enabled --quiet joysong@uat.service; then
  printf 'managed UAT must remain inactive and disabled between baseline validation and final cutover\n' >&2
  exit 2
fi
old_pid="$(systemctl_property_value "$old_service" MainPID)"
[[ "$old_pid" =~ ^[1-9][0-9]*$ && "$old_pid" != "1" && -r "/proc/$old_pid/environ" ]] || {
  printf 'old service process environment is unavailable\n' >&2
  exit 2
}
python3 - "$baseline_old_jar" "/proc/$old_pid/cmdline" <<'PY'
import pathlib
import sys

expected = pathlib.Path(sys.argv[1]).resolve(strict=True)
arguments = [item.decode("utf-8") for item in pathlib.Path(sys.argv[2]).read_bytes().split(b"\0") if item]
for argument in arguments:
    candidate = pathlib.Path(argument)
    try:
        if candidate.is_file() and candidate.resolve(strict=True) == expected:
            break
    except OSError:
        pass
else:
    raise SystemExit("old service MainPID no longer runs the captured old JAR")
PY
old_listener_pids="$(ss -H -ltnp 'sport = :8080' | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | sort -u)"
[[ "$old_listener_pids" == "$old_pid" ]] || {
  printf 'old backend listener on 8080 is not owned exclusively by the captured old service\n' >&2
  exit 2
}
current_database_identity_line="$(python3 - "$baseline_old_env" /etc/joysong/uat/joysong.env "/proc/$old_pid/environ" <<'PY'
import hashlib
import json
import pathlib
import re
import sys
from urllib.parse import urlsplit

LEGACY_OLD_ENV_LINE_ALLOWLIST = frozenset({
    (
        "SPRING_AUTOCONFIGURE_EXCLUDE",
        "5bf8aa57fc5a6bc547decf1cc6db63f10deb55a3c6c5df497d631fb3d95e1abf",
    ),
    (
        "OSS_PRIVATE_BUCKET_NAME",
        "733e034005783808dcc93b5c3683e47cd536f7d3cc6c1141dae9742304cd7069",
    ),
})

def consume_approved_legacy_line(previous_assignment_key, line_sha256, seen_lines):
    provenance = (previous_assignment_key, line_sha256)
    if provenance not in LEGACY_OLD_ENV_LINE_ALLOWLIST or provenance in seen_lines:
        return None
    seen_lines.add(provenance)
    return provenance

def read_environment(path_value, allow_legacy_lines=False):
    values = {}
    ignored_lines = []
    seen_legacy_lines = set()
    previous_assignment_key = None
    for raw in pathlib.Path(path_value).read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", ";")):
            continue
        if "=" not in line:
            if allow_legacy_lines:
                line_sha256 = hashlib.sha256(line.encode("utf-8")).hexdigest()
                provenance = consume_approved_legacy_line(
                    previous_assignment_key,
                    line_sha256,
                    seen_legacy_lines,
                )
                if provenance is not None:
                    ignored_lines.append(provenance)
                    continue
            raise SystemExit(f"unsupported environment line in {path_value}")
        key, value = line.split("=", 1)
        key = key.strip()
        if key in values:
            raise SystemExit(f"duplicate {key} in {path_value}")
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key] = value
        previous_assignment_key = key
    return values, ignored_lines

def read_process_environment(path_value):
    values = {}
    for item in pathlib.Path(path_value).read_bytes().split(b"\0"):
        if not item:
            continue
        key_bytes, separator, value_bytes = item.partition(b"=")
        if not separator:
            continue
        key = key_bytes.decode("utf-8")
        if key in values:
            raise SystemExit(f"duplicate {key} in old service process environment")
        values[key] = value_bytes.decode("utf-8")
    return values

def identity(values, label):
    url = values.get("DB_URL", "")
    if not url.startswith("jdbc:mysql://"):
        raise SystemExit(f"DB_URL is missing or is not MySQL in {label}")
    parsed = urlsplit("mysql://" + url[len("jdbc:mysql://"):])
    database = parsed.path.lstrip("/")
    if not parsed.hostname or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", database):
        raise SystemExit(f"DB_URL lacks a safe host/database in {label}")
    username = values.get("DB_USERNAME", "")
    if not username:
        raise SystemExit(f"DB_USERNAME is missing in {label}")
    return parsed.hostname.lower(), parsed.port or 3306, database, username

old_values, ignored_old_lines = read_environment(sys.argv[1], allow_legacy_lines=True)
new_values, ignored_new_lines = read_environment(sys.argv[2])
if ignored_new_lines:
    raise SystemExit("new UAT config unexpectedly ignored an environment line")
old_config = identity(old_values, "captured old config")
new_config = identity(new_values, "managed UAT config")
old_live = identity(read_process_environment(sys.argv[3]), "old service process")
if not old_config == new_config == old_live:
    raise SystemExit("old config, old live process and managed UAT config no longer use the same database")
digest = hashlib.sha256(
    json.dumps(old_config, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
).hexdigest()
normalized_old_lines = sorted(ignored_old_lines)
ignored_lines_digest = hashlib.sha256(
    json.dumps(
        normalized_old_lines,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")
).hexdigest()
print(
    f"{digest}|{old_config[0]}|{old_config[1]}|{old_config[2]}|"
    f"{len(normalized_old_lines)}|{ignored_lines_digest}"
)
PY
)"
IFS='|' read -r current_database_identity database_host database_port database_name \
  current_old_env_ignored_line_count current_old_env_ignored_line_sha256 \
  <<<"$current_database_identity_line"
[[ "$current_database_identity" == "$baseline_database_identity" ]] || {
  printf 'UAT database identity changed after baseline capture; refusing final cutover\n' >&2
  exit 2
}
[[ "$current_old_env_ignored_line_count" == "$baseline_old_env_ignored_line_count" &&
   "$current_old_env_ignored_line_sha256" == "$baseline_old_env_ignored_line_sha256" ]] || {
  printf 'legacy old environment ignored-line audit changed after baseline capture\n' >&2
  exit 2
}
printf 'Final cutover database: host=%s port=%s name=%s\n' "$database_host" "$database_port" "$database_name"
command -v nginx >/dev/null || { printf 'nginx is required\n' >&2; exit 2; }
command -v rsync >/dev/null || { printf 'rsync is required\n' >&2; exit 2; }
/usr/local/lib/joysong/validate-runtime-config.sh uat /etc/joysong/uat/joysong.env
/usr/local/lib/joysong/deploy-release.sh verify-stored uat "$baseline_tag"

old_uploads="$old_data_root/uploads"
[[ -d "$old_uploads" && ! -L "$old_uploads" ]] || { printf 'old uploads directory is required\n' >&2; exit 2; }
old_private=""
if [[ -d "$old_data_root/private" && ! -L "$old_data_root/private" ]]; then
  old_private="$old_data_root/private"
elif [[ -e "$old_data_root/private" ]]; then
  printf 'old private path is unsafe\n' >&2
  exit 2
fi
old_staging=""
if [[ -d "$old_data_root/upload-staging" && ! -L "$old_data_root/upload-staging" ]]; then
  old_staging="$old_data_root/upload-staging"
elif [[ -d "$old_data_root/staging" && ! -L "$old_data_root/staging" ]]; then
  old_staging="$old_data_root/staging"
elif [[ -e "$old_data_root/upload-staging" || -e "$old_data_root/staging" ]]; then
  printf 'old staging path is unsafe\n' >&2
  exit 2
fi

check_old_live_data_roots() {
  "$data_manifest_helper" check "$old_uploads"
  [[ -z "$old_private" ]] || "$data_manifest_helper" check "$old_private"
  [[ -z "$old_staging" ]] || "$data_manifest_helper" check "$old_staging"
}

# This first structural snapshot is intentionally before maintenance or either
# service stop. A later frozen snapshot closes the write-freeze TOCTOU window.
check_old_live_data_roots
validate_new_data_targets() {
  [[ -d /var/lib/joysong/uat && ! -L /var/lib/joysong/uat ]] || {
    printf 'new UAT data root is unsafe\n' >&2
    return 1
  }
  [[ "$(stat -c '%U:%G:%a' /var/lib/joysong/uat)" == "root:joysong-uat-release:710" ]] || {
    printf 'new UAT data root ownership or mode is unsafe\n' >&2
    return 1
  }
  local target
  for target in /var/lib/joysong/uat/uploads /var/lib/joysong/uat/private /var/lib/joysong/uat/upload-staging; do
    [[ -d "$target" && ! -L "$target" ]] || {
      printf 'new UAT data target is unsafe: %s\n' "$target" >&2
      return 1
    }
    "$data_manifest_helper" check "$target"
  done
}
validate_new_data_targets

web_group="$(sed -n 's/^WEB_GROUP=//p' /etc/joysong/deploy.env | tail -n 1)"
if [[ ! "$web_group" =~ ^[A-Za-z_][A-Za-z0-9_.-]*$ ]] || ! getent group "$web_group" >/dev/null; then
  printf 'WEB_GROUP is invalid in deployment host configuration\n' >&2
  exit 2
fi

verify_uat_service() {
  local pid cmdline resolved_jar
  systemctl is-active --quiet joysong@uat.service || return 1
  pid="$(systemctl_property_value joysong@uat.service MainPID)"
  resolved_jar="$(readlink -f /opt/joysong/uat/current/server.jar 2>/dev/null || true)"
  [[ "$pid" =~ ^[1-9][0-9]*$ && "$pid" != "1" && -r "/proc/$pid/cmdline" && -n "$resolved_jar" ]] || return 1
  cmdline="$(tr '\0' '\n' <"/proc/$pid/cmdline" 2>/dev/null || true)"
  { grep -Fxq /opt/joysong/uat/current/server.jar <<<"$cmdline" || grep -Fxq "$resolved_jar" <<<"$cmdline"; } || return 1
  curl --fail --silent --show-error --max-time 3 http://127.0.0.1:8081/actuator/health >/dev/null
}

nginx -t

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
maintenance_file=/var/lib/joysong-maintenance/uat
[[ ! -e "$maintenance_file" ]] || { printf 'an incomplete UAT deployment or cutover requires operator recovery\n' >&2; exit 2; }
cutover_parent="/var/backups/joysong/uat-cutover"
install -d -o root -g root -m 0700 "$cutover_parent"
runtime_backup_parent=/var/backups/joysong/uat/releases
install -d -o root -g root -m 0700 "$runtime_backup_parent"

command -v mysql >/dev/null || { printf 'mysql client is required for the pre-freeze capacity check\n' >&2; exit 2; }
read_env_value() {
  local key="$1" line value
  line="$(grep -E "^[[:space:]]*${key}=" /etc/joysong/uat/joysong.env | tail -n 1 || true)"
  [[ -n "$line" ]] || return 1
  value="${line#*=}"
  value="${value%$'\r'}"
  if [[ "$value" == \"*\" && "$value" == *\" ]]; then
    value="${value:1:${#value}-2}"
  elif [[ "$value" == \'*\' && "$value" == *\' ]]; then
    value="${value:1:${#value}-2}"
  fi
  printf '%s' "$value"
}
database_username="$(read_env_value DB_USERNAME)"
database_password="$(read_env_value DB_PASSWORD)"
database_size="$(MYSQL_PWD="$database_password" mysql \
  --protocol=TCP --host="$database_host" --port="$database_port" --user="$database_username" \
  --batch --skip-column-names \
  --execute="SELECT COALESCE(SUM(data_length + index_length), 0) FROM information_schema.tables WHERE table_schema = '${database_name}'")"
unset database_password MYSQL_PWD
[[ "$database_size" =~ ^[0-9]+$ ]] || { printf 'database size query failed\n' >&2; exit 2; }

old_data_sources=("$old_uploads")
[[ -z "$old_private" ]] || old_data_sources+=("$old_private")
[[ -z "$old_staging" ]] || old_data_sources+=("$old_staging")
new_data_sources=(
  /var/lib/joysong/uat/uploads
  /var/lib/joysong/uat/private
  /var/lib/joysong/uat/upload-staging
)
old_data_bytes="$(du -sb "${old_data_sources[@]}" | awk '{ total += $1 } END { print total + 0 }')"
new_data_bytes="$(du -sb "${new_data_sources[@]}" | awk '{ total += $1 } END { print total + 0 }')"
[[ "$old_data_bytes" =~ ^[0-9]+$ && "$new_data_bytes" =~ ^[0-9]+$ ]] || {
  printf 'persistent data size estimate failed\n' >&2
  exit 2
}
readonly capacity_floor_bytes=$((512 * 1024 * 1024))
declare -A required_by_device=() path_by_device=()
add_capacity_requirement() {
  local path="$1" bytes="$2" device current_required
  device="$(stat -c '%d' "$path")"
  current_required="${required_by_device[$device]:-0}"
  required_by_device["$device"]=$((current_required + bytes + capacity_floor_bytes))
  path_by_device["$device"]="$path"
}
add_capacity_requirement "$cutover_parent" $((old_data_bytes + new_data_bytes))
add_capacity_requirement "$runtime_backup_parent" $((database_size * 2))
for device in "${!required_by_device[@]}"; do
  capacity_path="${path_by_device[$device]}"
  available_bytes="$(df -PB1 "$capacity_path" | awk 'NR == 2 { print $4 }')"
  [[ "$available_bytes" =~ ^[0-9]+$ ]] || { printf 'available capacity could not be resolved\n' >&2; exit 2; }
  printf 'Cutover capacity: path=%s available=%s required=%s\n' \
    "$capacity_path" "$available_bytes" "${required_by_device[$device]}"
  ((available_bytes > required_by_device[$device])) || {
    printf 'insufficient capacity for frozen database and persistent-data backups\n' >&2
    exit 2
  }
done

cutover_backup="$cutover_parent/$timestamp"
[[ ! -e "$cutover_backup" ]] || { printf 'immutable cutover backup path already exists\n' >&2; exit 2; }
install -d -o root -g root -m 0700 "$cutover_backup"
install -o root -g root -m 0600 "$active_nginx_config" "$cutover_backup/old-nginx.conf"
nginx -T >"$cutover_backup/nginx-before.txt" 2>&1
systemctl status "$old_service" --no-pager >"$cutover_backup/old-service-before.txt" || true
systemctl status joysong@uat.service --no-pager >"$cutover_backup/new-service-before.txt" || true

old_was_enabled=false
systemctl is-enabled --quiet "$old_service" && old_was_enabled=true
old_stopped=false
nginx_replaced=false
new_config_staged="${active_nginx_config}.joysong-new.$$"
cutover_marker=/etc/joysong/uat/CUTOVER_COMPLETED
cutover_marker_stage="/etc/joysong/uat/.CUTOVER_COMPLETED.$$"
rollback_cutover() {
  local code=$? new_quiesced=false restored=false restore_staged
  trap - EXIT ERR INT TERM
  set +e
  rm -f "$new_config_staged"
  rm -f "$cutover_marker_stage"
  # The entry gate proved the canonical marker did not pre-exist this
  # transaction. Remove it unconditionally so an interrupt between the atomic
  # rename and the following instruction cannot leave a false success marker.
  rm -f "$cutover_marker"
  rm -f /run/joysong-uat-start-authorized
  # Never restart the old writer until the managed UAT writer is confirmed
  # stopped. Maintenance remains enabled on any incomplete recovery.
  systemctl disable --now joysong@uat.service >/dev/null 2>&1 || true
  if ! systemctl is-active --quiet joysong@uat.service; then
    new_quiesced=true
    if [[ "$nginx_replaced" == true ]]; then
      restore_staged="${active_nginx_config}.joysong-restore.$$"
      install -o root -g root -m 0644 "$cutover_backup/old-nginx.conf" "$restore_staged"
      mv -Tf "$restore_staged" "$active_nginx_config"
    fi
    if nginx -t && systemctl reload nginx; then
      if [[ "$old_stopped" == true ]]; then
        if [[ "$old_was_enabled" == true ]]; then
          systemctl enable "$old_service" >/dev/null 2>&1 || true
        fi
        systemctl restart "$old_service" || true
      fi
      if [[ "$old_stopped" != true ]] || systemctl is-active --quiet "$old_service"; then
        restored=true
        rm -f "$maintenance_file"
      fi
    fi
  fi
  if [[ "$new_quiesced" != true || "$restored" != true ]]; then
    printf 'CRITICAL: UAT cutover recovery is incomplete; maintenance remains enabled and the old writer was not started unless the new writer was confirmed stopped.\n' >&2
  fi
  printf 'UAT cutover failed; safe old Nginx/service restoration was attempted. Backup: %s\n' "$cutover_backup" >&2
  exit "$code"
}
trap rollback_cutover EXIT ERR INT TERM

# This is the write-freeze boundary. Both applications are stopped before the
# final database backup and file delta so the old tree is authoritative.
install -o root -g root -m 0600 /dev/null "$maintenance_file"
old_stopped=true
systemctl disable "$old_service"
systemctl stop "$old_service"
systemctl stop joysong@uat.service
if systemctl is-active --quiet "$old_service" || systemctl is-active --quiet joysong@uat.service; then
  printf 'application write freeze failed\n' >&2
  exit 1
fi
[[ ! -e "/proc/$old_pid" ]] || { printf 'captured old service MainPID still exists after freeze\n' >&2; exit 1; }
[[ -z "$(ss -H -ltnp 'sport = :8080')" ]] || {
  printf 'old backend listener on 8080 remains after write freeze\n' >&2
  exit 1
}
check_old_live_data_roots
validate_new_data_targets

/usr/local/lib/joysong/backup-runtime-state.sh uat "cutover-$timestamp" database \
  "$(basename "$old_service" .service)" "$baseline_tag"
install -d -o root -g root -m 0700 "$cutover_backup/old-data" "$cutover_backup/new-data-before-delta"
rsync -ax --numeric-ids "$old_uploads/" "$cutover_backup/old-data/uploads/"
rsync -ax --numeric-ids /var/lib/joysong/uat/uploads/ "$cutover_backup/new-data-before-delta/uploads/"
if [[ -n "$old_private" ]]; then
  rsync -ax --numeric-ids "$old_private/" "$cutover_backup/old-data/private/"
fi
rsync -ax --numeric-ids /var/lib/joysong/uat/private/ "$cutover_backup/new-data-before-delta/private/"
if [[ -n "$old_staging" ]]; then
  rsync -ax --numeric-ids "$old_staging/" "$cutover_backup/old-data/upload-staging/"
fi
rsync -ax --numeric-ids /var/lib/joysong/uat/upload-staging/ "$cutover_backup/new-data-before-delta/upload-staging/"

empty_source="$cutover_backup/empty-source"
install -d -o root -g root -m 0700 "$empty_source"
final_private_source="${old_private:-$empty_source}"
final_staging_source="${old_staging:-$empty_source}"
# Recheck immediately before the authoritative delta rsync. Even while the
# application is frozen, an out-of-band filesystem mutation must fail closed.
check_old_live_data_roots
rsync -ax --checksum --delete --chown="joysong-uat:$web_group" "$old_uploads/" /var/lib/joysong/uat/uploads/
rsync -ax --checksum --delete --chown=joysong-uat:joysong-uat "$final_private_source/" /var/lib/joysong/uat/private/
rsync -ax --checksum --delete --chown=joysong-uat:joysong-uat "$final_staging_source/" /var/lib/joysong/uat/upload-staging/
chown -R "joysong-uat:$web_group" /var/lib/joysong/uat/uploads
find /var/lib/joysong/uat/uploads -type d -exec chmod 2750 {} +
find /var/lib/joysong/uat/uploads -type f -exec chmod 0640 {} +
chown -R joysong-uat:joysong-uat /var/lib/joysong/uat/private /var/lib/joysong/uat/upload-staging
find /var/lib/joysong/uat/private /var/lib/joysong/uat/upload-staging -type d -exec chmod 0700 {} +
find /var/lib/joysong/uat/private /var/lib/joysong/uat/upload-staging -type f -exec chmod 0600 {} +
validate_new_data_targets

python3 - "$old_uploads" /var/lib/joysong/uat/uploads \
  "$final_private_source" /var/lib/joysong/uat/private \
  "$final_staging_source" /var/lib/joysong/uat/upload-staging <<'PY'
import hashlib
import pathlib
import sys

def digest(path: pathlib.Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()

arguments = sys.argv[1:]
for index in range(0, len(arguments), 2):
    source_value, target_value = arguments[index:index + 2]
    source = pathlib.Path(source_value)
    target = pathlib.Path(target_value)
    source_entries = {
        item.relative_to(source).as_posix(): "directory" if item.is_dir() else digest(item)
        for item in source.rglob("*")
    }
    target_entries = {
        item.relative_to(target).as_posix(): "directory" if item.is_dir() else digest(item)
        for item in target.rglob("*")
    }
    if source_entries != target_entries:
        raise SystemExit(f"final data tree mismatch: {source} -> {target}")
PY

systemctl enable joysong@uat.service
install -o root -g root -m 0600 /dev/null /run/joysong-uat-start-authorized
systemctl start joysong@uat.service
for _ in {1..30}; do
  verify_uat_service && break
  sleep 2
done
verify_uat_service || { printf 'new UAT failed identity/health after final data sync\n' >&2; exit 1; }

install -o root -g root -m 0644 "$new_nginx_config" "$new_config_staged"
mv -Tf "$new_config_staged" "$active_nginx_config"
nginx_replaced=true
nginx -t
systemctl reload nginx
curl --fail --silent --show-error --max-time 15 \
  --resolve api.joyingsong.net:443:127.0.0.1 \
  https://api.joyingsong.net/.well-known/joysong-release.json >"$cutover_backup/api-release.json"
curl --fail --silent --show-error --max-time 15 \
  --resolve joyingsong.net:443:127.0.0.1 \
  https://joyingsong.net/.well-known/joysong-release.json >"$cutover_backup/admin-release.json"
cmp --silent /opt/joysong/uat/current/release.json "$cutover_backup/api-release.json"
cmp --silent /opt/joysong/uat/current/release.json "$cutover_backup/admin-release.json"

systemctl disable "$old_service"
cat >"$cutover_marker_stage" <<EOF
CUTOVER_COMPLETED=true
COMPLETED_AT=$timestamp
OLD_SERVICE=$old_service
OLD_JAR=$baseline_old_jar
OLD_JAR_SHA256=$baseline_old_jar_sha256
OLD_ENV=$baseline_old_env
OLD_DATA_ROOT=$old_data_root
NGINX_CONFIG=$active_nginx_config
BACKUP_ROOT=$cutover_backup
EOF
chown root:root "$cutover_marker_stage"
chmod 0600 "$cutover_marker_stage"
mv -Tf "$cutover_marker_stage" "$cutover_marker"
trap - EXIT ERR INT TERM
rm -f "$maintenance_file"
printf 'UAT final cutover completed; old service is stopped/disabled but retained.\n'
printf 'Cutover backup and reversible old Nginx config: %s\n' "$cutover_backup"
