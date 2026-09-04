#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly RETAIN_BACKUPS=10
readonly MIN_FREE_BYTES=$((512 * 1024 * 1024))

environment="${1:-}"
label="${2:-}"
mode="${3:-}"
from_release="${4:-unknown}"
to_release="${5:-unknown}"
[[ "$EUID" -eq 0 ]] || { printf 'run as root\n' >&2; exit 2; }
(($# >= 3 && $# <= 5)) || { printf 'usage: %s <uat|prod> <label> <database|config-only> [from-release] [to-release]\n' "$0" >&2; exit 2; }
[[ "$environment" == "uat" || "$environment" == "prod" ]] || {
  printf 'environment must be uat or prod\n' >&2
  exit 2
}
[[ "$label" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || { printf 'backup label is invalid\n' >&2; exit 2; }
[[ "$mode" == "database" || "$mode" == "config-only" ]] || {
  printf 'mode must be database or config-only\n' >&2
  exit 2
}
[[ "$from_release" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ &&
   "$to_release" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || {
  printf 'backup release identity is invalid\n' >&2
  exit 2
}

environment_file="/etc/joysong/$environment/joysong.env"
/usr/local/lib/joysong/validate-runtime-config.sh "$environment" "$environment_file"
mysql_client_config="/etc/mysql/joysong-${environment}-backup.cnf"
data_root="/var/lib/joysong/$environment"
data_paths=("$data_root/uploads" "$data_root/private" "$data_root/upload-staging")
for data_path in "${data_paths[@]}"; do
  [[ -d "$data_path" && ! -L "$data_path" ]] || { printf 'persistent data path is unsafe: %s\n' "$data_path" >&2; exit 2; }
  unsafe_entry="$(find "$data_path" -xdev ! -type d ! -type f -print -quit)"
  [[ -z "$unsafe_entry" ]] || { printf 'persistent data contains a link or special file: %s\n' "$unsafe_entry" >&2; exit 2; }
done
persistent_data_bytes="$(du -sb "${data_paths[@]}" | awk '{ total += $1 } END { print total + 0 }')"
[[ "$persistent_data_bytes" =~ ^[0-9]+$ ]] || { printf 'persistent data size query failed\n' >&2; exit 2; }

read_value() {
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

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_parent="/var/backups/joysong/$environment/releases"
install -d -o root -g root -m 0700 "$backup_parent"

# Retention is an explicit operator action: deployment automation never deletes
# rollback material. Refuse to create an eleventh verified backup and print the
# exact directory so a root operator can inspect and remove one deliberately.
verified_backups=0
while IFS= read -r candidate; do
  [[ -f "$candidate/BACKUP_COMPLETE" && -f "$candidate/SHA256SUMS" ]] || continue
  grep -Eq '^BACKUP_COMPLETE=true$' "$candidate/BACKUP_COMPLETE" || continue
  (cd "$candidate" && sha256sum --check --status SHA256SUMS) || continue
  ((verified_backups += 1))
done < <(find "$backup_parent" -mindepth 1 -maxdepth 1 -type d -print)
if ((verified_backups >= RETAIN_BACKUPS)); then
  printf 'verified backup retention limit (%s) reached under %s; inspect and remove an exact old path manually\n' \
    "$RETAIN_BACKUPS" "$backup_parent" >&2
  exit 2
fi
backup_root="$backup_parent/${timestamp}-${label}"
[[ ! -e "$backup_root" ]] || { printf 'immutable backup path already exists\n' >&2; exit 2; }
install -d -o root -g root -m 0700 "$backup_root"
backup_complete=false
cleanup_failed_backup() {
  local code=$?
  trap - EXIT ERR INT TERM
  if [[ "$backup_complete" != true && -d "$backup_root" ]]; then
    rm -rf --one-file-system "$backup_root"
  fi
  return "$code"
}
trap cleanup_failed_backup EXIT ERR INT TERM
install -o root -g root -m 0600 "$environment_file" "$backup_root/joysong.env"
create_persistent_archive() {
  tar --create --gzip --one-file-system --numeric-owner \
    --file "$backup_root/persistent-data.tar.gz" --directory "$data_root" \
    uploads private upload-staging
  python3 - "$backup_root/persistent-data.tar.gz" <<'PY'
import pathlib
import sys
import tarfile

archive = pathlib.Path(sys.argv[1])
with tarfile.open(archive, "r:gz") as bundle:
    for member in bundle.getmembers():
        path = pathlib.PurePosixPath(member.name)
        if path.is_absolute() or ".." in path.parts or not (member.isdir() or member.isfile()):
            raise SystemExit(f"unsafe persistent-data archive member: {member.name}")
PY
}
database_host=""
database_port=""
database_name=""
database_dump_sha=""

if [[ "$mode" == "database" ]]; then
  command -v mysql >/dev/null || { printf 'mysql client is required\n' >&2; exit 2; }
  command -v mysqldump >/dev/null || { printf 'mysqldump is required\n' >&2; exit 2; }
  command -v gzip >/dev/null || { printf 'gzip is required\n' >&2; exit 2; }
  [[ -f "$mysql_client_config" && ! -L "$mysql_client_config" &&
     "$(stat -c '%U:%G:%a' "$mysql_client_config")" == "root:root:600" ]] || {
    printf 'database backup requires %s as a root:root mode 0600 regular file\n' "$mysql_client_config" >&2
    exit 2
  }
  database_url="$(read_value DB_URL)"
  mapfile -t database_parts < <(python3 - "$database_url" <<'PY'
import sys
from urllib.parse import urlsplit

value = sys.argv[1]
if not value.startswith("jdbc:mysql://"):
    raise SystemExit("unsupported database URL")
parsed = urlsplit("mysql://" + value[len("jdbc:mysql://"):])
if not parsed.hostname or not parsed.path.lstrip("/"):
    raise SystemExit("database host or name is missing")
print(parsed.hostname)
print(parsed.port or 3306)
print(parsed.path.lstrip("/"))
PY
  )
  ((${#database_parts[@]} == 3)) || { printf 'database URL parsing failed\n' >&2; exit 2; }
  database_host="${database_parts[0]}"
  database_port="${database_parts[1]}"
  database_name="${database_parts[2]}"
  printf 'Backing up database host: %s\n' "$database_host"
  printf 'Backing up database name: %s\n' "$database_name"
  database_size="$(mysql --defaults-extra-file="$mysql_client_config" \
    --protocol=TCP --host="$database_host" --port="$database_port" \
    --batch --skip-column-names \
    --execute="SELECT COALESCE(SUM(data_length + index_length), 0) FROM information_schema.tables WHERE table_schema = '${database_name}'")"
  [[ "$database_size" =~ ^[0-9]+$ ]] || { printf 'database size query failed\n' >&2; exit 2; }
  available_bytes="$(df -PB1 "$backup_parent" | awk 'NR == 2 {print $4}')"
  required_bytes=$((database_size * 2 + persistent_data_bytes + MIN_FREE_BYTES))
  printf 'Backup capacity: available=%s required=%s estimated_database=%s\n' \
    "$available_bytes" "$required_bytes" "$database_size"
  ((available_bytes > required_bytes)) || { printf 'insufficient disk capacity for verified database backup\n' >&2; exit 2; }
  database_engines="$(mysql --defaults-extra-file="$mysql_client_config" \
    --protocol=TCP --host="$database_host" --port="$database_port" \
    --batch --skip-column-names \
    --execute="SELECT COALESCE(engine, 'NULL'), COUNT(*) FROM information_schema.tables WHERE table_schema = '${database_name}' AND table_type = 'BASE TABLE' GROUP BY engine ORDER BY engine")"
  while IFS=$'\t' read -r database_engine table_count; do
    [[ -z "$database_engine" ]] && continue
    [[ "$database_engine" == "InnoDB" && "$table_count" =~ ^[0-9]+$ ]] || {
      printf 'online logical backup requires every base table to use InnoDB\n' >&2
      exit 2
    }
  done <<<"$database_engines"
  database_events_before="$(mysql --defaults-extra-file="$mysql_client_config" \
    --protocol=TCP --host="$database_host" --port="$database_port" \
    --batch --skip-column-names \
    --execute="SELECT COUNT(*) FROM information_schema.events WHERE event_schema = '${database_name}'")"
  [[ "$database_events_before" == "0" ]] || {
    printf 'online logical backup requires the source database to contain no scheduled events\n' >&2
    exit 2
  }
  create_persistent_archive
  mysqldump --defaults-extra-file="$mysql_client_config" \
    --protocol=TCP \
    --host="$database_host" \
    --port="$database_port" \
    --single-transaction \
    --quick \
    --routines \
    --triggers \
    --hex-blob \
    --set-gtid-purged=OFF \
    --no-tablespaces \
    --no-create-db \
    "$database_name" |
    gzip -9 >"$backup_root/database.sql.gz"
  gzip -t "$backup_root/database.sql.gz"
  [[ -s "$backup_root/database.sql.gz" ]] || { printf 'database backup is empty\n' >&2; exit 1; }
  if gzip -cd "$backup_root/database.sql.gz" |
      grep -Ei '^[[:space:]]*(CREATE[[:space:]]+DATABASE|USE[[:space:]])' >/dev/null; then
    printf 'database backup unexpectedly contains CREATE DATABASE or USE\n' >&2
    exit 2
  fi
  database_events_after="$(mysql --defaults-extra-file="$mysql_client_config" \
    --protocol=TCP --host="$database_host" --port="$database_port" \
    --batch --skip-column-names \
    --execute="SELECT COUNT(*) FROM information_schema.events WHERE event_schema = '${database_name}'")"
  [[ "$database_events_after" == "0" ]] || {
    printf 'source database scheduled events changed during backup\n' >&2
    exit 2
  }
  database_dump_sha="$(sha256sum "$backup_root/database.sql.gz" | awk '{ print $1 }')"
fi

if [[ "$mode" == "database" ]]; then
  (cd "$backup_root" && sha256sum joysong.env persistent-data.tar.gz database.sql.gz >SHA256SUMS &&
    sha256sum --check --status SHA256SUMS)
else
  available_bytes="$(df -PB1 "$backup_parent" | awk 'NR == 2 {print $4}')"
  required_bytes=$((persistent_data_bytes + MIN_FREE_BYTES))
  ((available_bytes > required_bytes)) || { printf 'insufficient disk capacity for configuration and persistent-data backup\n' >&2; exit 2; }
  create_persistent_archive
  (cd "$backup_root" && sha256sum joysong.env persistent-data.tar.gz >SHA256SUMS && sha256sum --check --status SHA256SUMS)
fi
{
  printf 'BACKUP_COMPLETE=true\n'
  printf 'ENVIRONMENT=%s\n' "$environment"
  printf 'LABEL=%s\n' "$label"
  printf 'MODE=%s\n' "$mode"
  printf 'CREATED_AT=%s\n' "$timestamp"
  printf 'FROM_RELEASE=%s\n' "$from_release"
  printf 'TO_RELEASE=%s\n' "$to_release"
  printf 'BACKUP_PATH=%s\n' "$backup_root"
  printf 'CONFIG_SHA256=%s\n' "$(sha256sum "$backup_root/joysong.env" | awk '{ print $1 }')"
  printf 'PERSISTENT_DATA_SHA256=%s\n' "$(sha256sum "$backup_root/persistent-data.tar.gz" | awk '{ print $1 }')"
  printf 'PERSISTENT_DATA_PATH=%s\n' "$backup_root/persistent-data.tar.gz"
  printf 'PERSISTENT_DATA_RESTORE_VERIFIED=false\n'
  if [[ "$mode" == "database" ]]; then
    printf 'DATABASE_HOST=%s\n' "$database_host"
    printf 'DATABASE_PORT=%s\n' "$database_port"
    printf 'DATABASE_NAME=%s\n' "$database_name"
    printf 'DATABASE_DUMP_SHA256=%s\n' "$database_dump_sha"
    printf 'DATABASE_DUMP_PATH=%s\n' "$backup_root/database.sql.gz"
    printf 'DATABASE_RESTORE_VERIFIED=false\n'
  fi
} >"$backup_root/BACKUP_COMPLETE"
chmod 0600 "$backup_root/BACKUP_COMPLETE" "$backup_root/SHA256SUMS"
backup_complete=true
trap - EXIT ERR INT TERM
printf 'Runtime backup completed: %s\n' "$backup_root"
printf 'BACKUP_PATH=%s\n' "$backup_root"
