#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

usage() {
  printf 'usage: %s <verified-backup-directory> <myapp_worktree_restore_name> <root-mysql-client.cnf> [--execute]\n' "$0" >&2
  exit 2
}

[[ "$EUID" -eq 0 ]] || { printf 'run as root\n' >&2; exit 2; }
(($# == 3 || $# == 4)) || usage
backup_input="$1"
target_database="$2"
mysql_client_config="$3"
execute_mode="${4:-}"
[[ -z "$execute_mode" || "$execute_mode" == "--execute" ]] || usage
[[ "$target_database" =~ ^myapp_worktree_restore_[A-Za-z0-9_]+$ ]] || {
  printf 'restore target must be a new myapp_worktree_restore_* database\n' >&2
  exit 2
}
mysql_client_config="$(readlink -f -- "$mysql_client_config")"
[[ "$mysql_client_config" == /root/* || "$mysql_client_config" == /etc/mysql/* ]] || {
  printf 'MySQL restore credentials must use a canonical root-only file below /root or /etc/mysql\n' >&2
  exit 2
}
[[ -f "$mysql_client_config" && ! -L "$mysql_client_config" &&
   "$(stat -c '%U:%G:%a' "$mysql_client_config")" == "root:root:600" ]] || {
  printf 'MySQL restore credential file must be root:root mode 0600\n' >&2
  exit 2
}

backup_root="$(readlink -f -- "$backup_input")"
[[ "$backup_root" == /var/backups/joysong/uat/releases/* && -d "$backup_root" && ! -L "$backup_root" ]] || {
  printf 'backup must be a canonical UAT runtime-backup directory\n' >&2
  exit 2
}
marker="$backup_root/BACKUP_COMPLETE"
dump="$backup_root/database.sql.gz"
environment_file="$backup_root/joysong.env"
[[ -f "$marker" && ! -L "$marker" && -f "$dump" && ! -L "$dump" &&
   -f "$environment_file" && ! -L "$environment_file" && -f "$backup_root/SHA256SUMS" ]] || {
  printf 'backup is incomplete or contains unsafe paths\n' >&2
  exit 2
}
(cd "$backup_root" && sha256sum --check --status SHA256SUMS) || {
  printf 'backup checksum verification failed\n' >&2
  exit 2
}
gzip -t "$dump"

read_marker_value() {
  local key="$1" count value
  count="$(grep -Ec "^${key}=" "$marker" || true)"
  [[ "$count" == "1" ]] || { printf 'backup marker requires exactly one %s\n' "$key" >&2; exit 2; }
  value="$(sed -n "s/^${key}=//p" "$marker")"
  [[ -n "$value" && "$value" != *$'\r'* && "$value" != *$'\n'* ]] || {
    printf 'backup marker %s is empty or unsafe\n' "$key" >&2
    exit 2
  }
  printf '%s' "$value"
}

[[ "$(read_marker_value BACKUP_COMPLETE)" == true &&
   "$(read_marker_value ENVIRONMENT)" == uat &&
   "$(read_marker_value MODE)" == database ]] || {
  printf 'backup marker is not a completed UAT database backup\n' >&2
  exit 2
}
[[ "$(read_marker_value BACKUP_PATH)" == "$backup_root" ]] || {
  printf 'backup marker path does not match the selected directory\n' >&2
  exit 2
}
expected_dump_sha="$(read_marker_value DATABASE_DUMP_SHA256)"
[[ "$expected_dump_sha" =~ ^[0-9a-f]{64}$ &&
   "$(sha256sum "$dump" | awk '{ print $1 }')" == "$expected_dump_sha" ]] || {
  printf 'database dump identity mismatch\n' >&2
  exit 2
}
python3 - "$dump" <<'PY'
import gzip
import pathlib
import re
import sys

unsafe = re.compile(rb"^[\t ]*(?:CREATE[\t ]+DATABASE|DROP[\t ]+DATABASE|USE[\t ])", re.IGNORECASE)
with gzip.open(pathlib.Path(sys.argv[1]), "rb") as stream:
    for number, line in enumerate(stream, 1):
        if unsafe.search(line):
            raise SystemExit(f"dump selects or mutates a database at line {number}")
PY

read_env_value() {
  local key="$1" line value
  line="$(grep -E "^[[:space:]]*${key}=" "$environment_file" | tail -n 1 || true)"
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

database_url="$(read_env_value DB_URL)"
mapfile -t database_parts < <(python3 - "$database_url" <<'PY'
import re
import sys
from urllib.parse import urlsplit

value = sys.argv[1]
if not value.startswith("jdbc:mysql://"):
    raise SystemExit("unsupported database URL")
parsed = urlsplit("mysql://" + value[len("jdbc:mysql://"):])
source = parsed.path.lstrip("/")
if not parsed.hostname or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", source):
    raise SystemExit("database host or source name is invalid")
print(parsed.hostname.lower())
print(parsed.port or 3306)
print(source)
PY
)
((${#database_parts[@]} == 3)) || { printf 'backup database identity parsing failed\n' >&2; exit 2; }
database_host="${database_parts[0]}"
database_port="${database_parts[1]}"
source_database="${database_parts[2]}"
[[ "$(read_marker_value DATABASE_HOST)" == "$database_host" &&
   "$(read_marker_value DATABASE_PORT)" == "$database_port" &&
   "$(read_marker_value DATABASE_NAME)" == "$source_database" ]] || {
  printf 'backup environment and marker database identities differ\n' >&2
  exit 2
}
printf 'Restore source: host=%s database=%s backup=%s\n' "$database_host" "$source_database" "$backup_root"
printf 'Restore target: host=%s database=%s\n' "$database_host" "$target_database"

exists="$(mysql --defaults-extra-file="$mysql_client_config" \
  --protocol=TCP --host="$database_host" --port="$database_port" \
  --batch --skip-column-names \
  --execute="SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '${target_database}'")"
[[ "$exists" == "0" ]] || { printf 'restore target database already exists; it will never be overwritten or dropped\n' >&2; exit 2; }
if [[ -z "$execute_mode" ]]; then
  printf 'Dry run passed. Re-run with --execute to create and restore only this new database.\n'
  exit 0
fi

attempt_parent=/var/backups/joysong/uat/restore-attempts
install -d -o root -g root -m 0700 "$attempt_parent"
attempt="$attempt_parent/$(date -u +%Y%m%dT%H%M%SZ)-$$-$target_database"
install -d -o root -g root -m 0700 "$attempt"
restore_complete=false
record_restore_result() {
  local code=$?
  trap - EXIT ERR INT TERM
  if [[ "$restore_complete" != true ]]; then
    printf 'RESTORE_COMPLETE=false\nTARGET_DATABASE=%s\nBACKUP_PATH=%s\nEXIT_CODE=%s\n' \
      "$target_database" "$backup_root" "$code" >"$attempt/RESTORE_FAILED"
    printf 'restore failed; partial target is retained for inspection and was not dropped: %s\n' "$target_database" >&2
  fi
  return "$code"
}
trap record_restore_result EXIT ERR INT TERM

mysql --defaults-extra-file="$mysql_client_config" \
  --protocol=TCP --host="$database_host" --port="$database_port" \
  --execute="CREATE DATABASE \`${target_database}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
gzip -cd "$dump" | mysql --defaults-extra-file="$mysql_client_config" \
  --protocol=TCP --host="$database_host" --port="$database_port" \
  "$target_database"
table_count="$(mysql --defaults-extra-file="$mysql_client_config" \
  --protocol=TCP --host="$database_host" --port="$database_port" \
  --batch --skip-column-names \
  --execute="SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '${target_database}'")"
[[ "$table_count" =~ ^[1-9][0-9]*$ ]] || { printf 'restored database contains no tables\n' >&2; exit 1; }
printf 'RESTORE_COMPLETE=true\nTARGET_DATABASE=%s\nBACKUP_PATH=%s\nDUMP_SHA256=%s\nTABLE_COUNT=%s\n' \
  "$target_database" "$backup_root" "$expected_dump_sha" "$table_count" >"$attempt/RESTORE_COMPLETE"
chmod 0600 "$attempt/RESTORE_COMPLETE"
restore_complete=true
trap - EXIT ERR INT TERM
printf 'Isolated UAT restore completed: database=%s audit=%s\n' "$target_database" "$attempt"
printf 'Application-level verification is still required before any recovery decision.\n'
