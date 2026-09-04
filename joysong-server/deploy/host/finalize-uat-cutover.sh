#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

usage() {
  printf '%s\n' \
    "usage: $0 <old-service> <old-data-root> <new-nginx-conf> <active-nginx-conf>" >&2
  exit 2
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
if [[ ! -f /etc/joysong/uat/BASELINE_CAPTURED ]] ||
   ! grep -Eq '^BASELINE_CAPTURED=true$' /etc/joysong/uat/BASELINE_CAPTURED; then
  printf 'successful UAT baseline capture is required\n' >&2
  exit 2
fi
[[ ! -e /etc/joysong/uat/CUTOVER_COMPLETED ]] || {
  printf 'UAT final cutover was already completed\n' >&2
    exit 2
  }
read_baseline_value() {
  local key="$1" marker=/etc/joysong/uat/BASELINE_CAPTURED count value
  count="$(grep -Ec "^${key}=" "$marker" || true)"
  [[ "$count" == "1" ]] || { printf 'baseline gate must contain exactly one %s\n' "$key" >&2; exit 2; }
  value="$(sed -n "s/^${key}=//p" "$marker")"
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
[[ "$baseline_tag" == "v0.0.0-uat.0" ]] || { printf 'unexpected baseline release tag\n' >&2; exit 2; }
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
old_pid="$(systemctl show --property MainPID --value "$old_service" 2>/dev/null || true)"
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

def read_environment(path_value: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in pathlib.Path(path_value).read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", ";")):
            continue
        if "=" not in line:
            raise SystemExit(f"unsupported environment line in {path_value}")
        key, value = line.split("=", 1)
        key = key.strip()
        if key in values:
            raise SystemExit(f"duplicate {key} in {path_value}")
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key] = value
    return values

def read_process_environment(path_value: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for item in pathlib.Path(path_value).read_bytes().split(b"\0"):
        if not item:
            continue
        key_bytes, separator, value_bytes = item.partition(b"=")
        if separator:
            values[key_bytes.decode("utf-8")] = value_bytes.decode("utf-8")
    return values

def identity(values: dict[str, str], label: str):
    url = values.get("DB_URL", "")
    if not url.startswith("jdbc:mysql://"):
        raise SystemExit(f"DB_URL is missing or is not MySQL in {label}")
    parsed = urlsplit("mysql://" + url.removeprefix("jdbc:mysql://"))
    database = parsed.path.lstrip("/")
    username = values.get("DB_USERNAME", "")
    if not parsed.hostname or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", database) or not username:
        raise SystemExit(f"database identity is incomplete in {label}")
    return parsed.hostname.lower(), parsed.port or 3306, database, username

old_config = identity(read_environment(sys.argv[1]), "captured old config")
new_config = identity(read_environment(sys.argv[2]), "managed UAT config")
old_live = identity(read_process_environment(sys.argv[3]), "old service process")
if not old_config == new_config == old_live:
    raise SystemExit("old config, old live process and managed UAT config no longer use the same database")
digest = hashlib.sha256(json.dumps(old_config, separators=(",", ":"), ensure_ascii=True).encode()).hexdigest()
print(f"{digest}|{old_config[0]}|{old_config[1]}|{old_config[2]}")
PY
)"
IFS='|' read -r current_database_identity database_host database_port database_name <<<"$current_database_identity_line"
[[ "$current_database_identity" == "$baseline_database_identity" ]] || {
  printf 'UAT database identity changed after baseline capture; refusing final cutover\n' >&2
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

validate_tree() {
  local label="$1" root="$2" unsafe
  unsafe="$(find "$root" -xdev ! -type d ! -type f -print -quit)"
  [[ -z "$unsafe" ]] || {
    printf '%s contains a link or special file: %s\n' "$label" "$unsafe" >&2
    exit 2
  }
}

validate_tree old-uploads "$old_uploads"
[[ -z "$old_private" ]] || validate_tree old-private "$old_private"
[[ -z "$old_staging" ]] || validate_tree old-staging "$old_staging"
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
    validate_tree new-uat-data "$target"
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
  pid="$(systemctl show --property MainPID --value joysong@uat.service 2>/dev/null || true)"
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
validate_tree frozen-old-uploads "$old_uploads"
[[ -z "$old_private" ]] || validate_tree frozen-old-private "$old_private"
[[ -z "$old_staging" ]] || validate_tree frozen-old-staging "$old_staging"
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
rsync -ax --checksum --delete --chown="joysong-uat:$web_group" "$old_uploads/" /var/lib/joysong/uat/uploads/
rsync -ax --checksum --delete --chown=joysong-uat:joysong-uat "$final_private_source/" /var/lib/joysong/uat/private/
rsync -ax --checksum --delete --chown=joysong-uat:joysong-uat "$final_staging_source/" /var/lib/joysong/uat/upload-staging/
chown -R "joysong-uat:$web_group" /var/lib/joysong/uat/uploads
find /var/lib/joysong/uat/uploads -type d -exec chmod 2750 {} +
find /var/lib/joysong/uat/uploads -type f -exec chmod 0640 {} +
chown -R joysong-uat:joysong-uat /var/lib/joysong/uat/private /var/lib/joysong/uat/upload-staging
find /var/lib/joysong/uat/private /var/lib/joysong/uat/upload-staging -type d -exec chmod 0700 {} +
find /var/lib/joysong/uat/private /var/lib/joysong/uat/upload-staging -type f -exec chmod 0600 {} +

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
