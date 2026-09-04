#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

usage() {
  printf '%s\n' \
    "usage: $0 <old-service> <old-server.jar> <old-admin-dir> <old-env-file>" \
    '          <old-data-root> <verified-db-backup-file> <40-char-commit>' >&2
  exit 2
}

[[ "$EUID" -eq 0 ]] || { printf 'run as root\n' >&2; exit 2; }
(($# == 7)) || usage

install -d -o root -g root -m 0755 /run/lock
exec 9>/run/lock/joysong-uat-deploy.lock
flock -n 9 || { printf 'another UAT release or migration operation is active\n' >&2; exit 2; }

old_service="$1"
old_jar="$2"
old_admin="$3"
old_env="$4"
old_data_root="$5"
database_backup="$6"
commit="$7"
readonly tag=v0.0.0-uat.0
readonly release_root="/opt/joysong/uat/releases/$tag"
readonly new_env=/etc/joysong/uat/joysong.env

[[ "$old_service" =~ ^[A-Za-z0-9@_.-]+$ ]] || usage
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || usage
[[ -f "$old_jar" && -r "$old_jar" ]] || { printf 'old JAR is unavailable\n' >&2; exit 2; }
[[ -d "$old_admin" && -f "$old_admin/index.html" ]] || { printf 'old Admin build is unavailable\n' >&2; exit 2; }
unsafe_admin_entry="$(find "$old_admin" -xdev ! -type d ! -type f -print -quit)"
[[ -z "$unsafe_admin_entry" ]] || {
  printf 'old Admin build contains a link or special file: %s\n' "$unsafe_admin_entry" >&2
  exit 2
}
[[ -f "$old_env" && -r "$old_env" ]] || { printf 'old environment file is unavailable\n' >&2; exit 2; }
[[ -d "$old_data_root" && ! -L "$old_data_root" ]] || { printf 'old data root is unavailable or unsafe\n' >&2; exit 2; }
old_env="$(readlink -f -- "$old_env")"
old_data_root="$(readlink -f -- "$old_data_root")"
old_jar="$(readlink -f -- "$old_jar")"
[[ "$old_env" == /* && "$old_data_root" == /* && "$old_jar" == /* ]] || {
  printf 'old paths could not be canonicalized\n' >&2
  exit 2
}
old_jar_sha256="$(sha256sum "$old_jar" | awk '{ print $1 }')"
[[ "$old_jar_sha256" =~ ^[0-9a-f]{64}$ ]] || { printf 'old JAR SHA-256 is invalid\n' >&2; exit 2; }
old_uploads="$old_data_root/uploads"
[[ -d "$old_uploads" && ! -L "$old_uploads" ]] || { printf 'old data root must contain a real uploads directory\n' >&2; exit 2; }
old_private=""
if [[ -d "$old_data_root/private" && ! -L "$old_data_root/private" ]]; then
  old_private="$old_data_root/private"
elif [[ -e "$old_data_root/private" ]]; then
  printf 'old private path exists but is not a real directory\n' >&2
  exit 2
fi
old_staging=""
if [[ -d "$old_data_root/upload-staging" && ! -L "$old_data_root/upload-staging" ]]; then
  old_staging="$old_data_root/upload-staging"
elif [[ -d "$old_data_root/staging" && ! -L "$old_data_root/staging" ]]; then
  old_staging="$old_data_root/staging"
elif [[ -e "$old_data_root/upload-staging" || -e "$old_data_root/staging" ]]; then
  printf 'old staging path exists but is not a real directory\n' >&2
  exit 2
fi
[[ -s "$database_backup" && -r "$database_backup" ]] || { printf 'verified gzip database backup is required\n' >&2; exit 2; }
gzip -t "$database_backup" || { printf 'database backup is not a valid gzip stream\n' >&2; exit 2; }
[[ -s "$new_env" ]] || { printf 'prepare the isolated UAT environment file first\n' >&2; exit 2; }
[[ ! -e "$release_root" ]] || { printf 'baseline release already exists\n' >&2; exit 2; }
[[ ! -e /etc/joysong/uat/BASELINE_CAPTURED ]] || { printf 'baseline gate already exists\n' >&2; exit 2; }
[[ ! -e /opt/joysong/uat/current && ! -L /opt/joysong/uat/current ]] || {
  printf 'managed UAT current link already exists; refusing to overwrite it\n' >&2
  exit 2
}
[[ ! -e /opt/joysong/uat/.current.new && ! -L /opt/joysong/uat/.current.new ]] || {
  printf 'stale managed UAT link staging path exists\n' >&2
  exit 2
}
if systemctl is-active --quiet joysong@uat.service || systemctl is-enabled --quiet joysong@uat.service; then
  printf 'managed UAT unit must be inactive and disabled before first baseline capture\n' >&2
  exit 2
fi
[[ "$old_service" != "joysong@uat.service" && "$old_service" != "joysong@uat" ]] || {
  printf 'old service must be independent from the managed UAT unit\n' >&2
  exit 2
}
systemctl is-active --quiet "$old_service" || { printf 'old service must remain active\n' >&2; exit 2; }
old_service="$(systemctl show --property Id --value "$old_service" 2>/dev/null || true)"
[[ "$old_service" =~ ^[A-Za-z0-9@_.-]+\.service$ ]] || { printf 'old service identity is invalid\n' >&2; exit 2; }
old_pid="$(systemctl show --property MainPID --value "$old_service" 2>/dev/null || true)"
[[ "$old_pid" =~ ^[1-9][0-9]*$ && "$old_pid" != "1" && -r "/proc/$old_pid/environ" ]] || {
  printf 'old service process environment is unavailable\n' >&2
  exit 2
}
python3 - "$old_jar" "/proc/$old_pid/cmdline" <<'PY'
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
    raise SystemExit("old service MainPID does not run the supplied old JAR")
PY
old_listener_pids="$(ss -H -ltnp 'sport = :8080' | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | sort -u)"
[[ "$old_listener_pids" == "$old_pid" ]] || {
  printf 'old backend listener on 8080 is not owned exclusively by the old service MainPID\n' >&2
  exit 2
}
command -v rsync >/dev/null || { printf 'rsync is required\n' >&2; exit 2; }

validate_persistent_tree() {
  local label="$1" source="$2" unsafe
  unsafe="$(find "$source" -xdev ! -type d ! -type f -print -quit)"
  [[ -z "$unsafe" ]] || {
    printf '%s contains a link or special file and cannot be migrated: %s\n' "$label" "$unsafe" >&2
    exit 2
  }
}

validate_persistent_tree uploads "$old_uploads"
[[ -z "$old_private" ]] || validate_persistent_tree private "$old_private"
[[ -z "$old_staging" ]] || validate_persistent_tree staging "$old_staging"

for target in /var/lib/joysong/uat/uploads /var/lib/joysong/uat/private /var/lib/joysong/uat/upload-staging; do
  [[ -d "$target" && ! -L "$target" ]] || {
    printf 'new UAT data target is unavailable or unsafe: %s\n' "$target" >&2
    exit 2
  }
  existing="$(find "$target" -mindepth 1 -xdev -print -quit)"
  if [[ -n "$existing" ]]; then
    printf 'new UAT data target must be empty before baseline migration: %s\n' "$target" >&2
    exit 2
  fi
done

[[ -r /etc/joysong/deploy.env ]] || { printf 'deployment host configuration is unavailable\n' >&2; exit 2; }
web_group="$(sed -n 's/^WEB_GROUP=//p' /etc/joysong/deploy.env | tail -n 1)"
if [[ ! "$web_group" =~ ^[A-Za-z_][A-Za-z0-9_.-]*$ ]] || ! getent group "$web_group" >/dev/null; then
  printf 'WEB_GROUP is invalid in deployment host configuration\n' >&2
  exit 2
fi

database_identity_line="$(python3 - "$old_env" "$new_env" "/proc/$old_pid/environ" <<'PY'
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
        if not separator:
            continue
        key = key_bytes.decode("utf-8")
        if key in values:
            raise SystemExit(f"duplicate {key} in old service process environment")
        values[key] = value_bytes.decode("utf-8")
    return values

def identity(values: dict[str, str], label: str):
    url = values.get("DB_URL", "")
    if not url.startswith("jdbc:mysql://"):
        raise SystemExit(f"DB_URL is missing or is not MySQL in {label}")
    parsed = urlsplit("mysql://" + url.removeprefix("jdbc:mysql://"))
    database = parsed.path.lstrip("/")
    if not parsed.hostname or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", database):
        raise SystemExit(f"DB_URL lacks a safe host/database in {label}")
    username = values.get("DB_USERNAME", "")
    if not username:
        raise SystemExit(f"DB_USERNAME is missing in {label}")
    return parsed.hostname.lower(), parsed.port or 3306, database, username

old_identity = identity(read_environment(sys.argv[1]), sys.argv[1])
new_identity = identity(read_environment(sys.argv[2]), sys.argv[2])
live_identity = identity(read_process_environment(sys.argv[3]), "old service process")
if not old_identity == new_identity == live_identity:
    raise SystemExit(
        "old config, old live process and new UAT config must use the exact same DB host, port, name and username"
    )
digest = hashlib.sha256(
    json.dumps(old_identity, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
).hexdigest()
print(f"{digest}|{old_identity[0]}|{old_identity[1]}|{old_identity[2]}")
PY
)"
IFS='|' read -r database_identity_sha database_identity_host database_identity_port database_identity_name <<<"$database_identity_line"
[[ "$database_identity_sha" =~ ^[0-9a-f]{64}$ ]] || { printf 'database identity digest is invalid\n' >&2; exit 2; }
printf 'Verified same UAT database: host=%s port=%s name=%s\n' \
  "$database_identity_host" "$database_identity_port" "$database_identity_name"

/usr/local/lib/joysong/validate-runtime-config.sh uat "$new_env"

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_parent="/var/backups/joysong/uat-bootstrap"
backup_root="$backup_parent/$timestamp"
stage="/opt/joysong/uat/releases/.stage-${tag}.$$"
migration_started=false
backup_owned=false
marker_stage="/etc/joysong/uat/.BASELINE_CAPTURED.$$"

# Account conservatively for the immutable bootstrap backup, the initial data
# copy, the baseline release (Admin exists both expanded and archived), and a
# safety floor on every destination filesystem. Requirements are accumulated
# when those destinations share the same ECS volume.
readonly capacity_floor_bytes=$((512 * 1024 * 1024))
old_data_sources=("$old_uploads")
[[ -z "$old_private" ]] || old_data_sources+=("$old_private")
[[ -z "$old_staging" ]] || old_data_sources+=("$old_staging")
old_data_bytes="$(du -sb "${old_data_sources[@]}" | awk '{ total += $1 } END { print total + 0 }')"
old_admin_bytes="$(du -sb "$old_admin" | awk '{ print $1 }')"
old_jar_bytes="$(stat -c '%s' "$old_jar")"
database_backup_bytes="$(stat -c '%s' "$database_backup")"
for bytes in "$old_data_bytes" "$old_admin_bytes" "$old_jar_bytes" "$database_backup_bytes"; do
  [[ "$bytes" =~ ^[0-9]+$ ]] || { printf 'capacity estimate is invalid\n' >&2; exit 2; }
done
install -d -o root -g root -m 0700 "$backup_parent"
declare -A required_by_device=() path_by_device=()
add_capacity_requirement() {
  local path="$1" bytes="$2" device current_required
  device="$(stat -c '%d' "$path")"
  current_required="${required_by_device[$device]:-0}"
  required_by_device["$device"]=$((current_required + bytes + capacity_floor_bytes))
  path_by_device["$device"]="$path"
}
add_capacity_requirement "$backup_parent" $((old_data_bytes + old_admin_bytes + old_jar_bytes + database_backup_bytes))
add_capacity_requirement /var/lib/joysong/uat "$old_data_bytes"
add_capacity_requirement /opt/joysong/uat/releases $((old_jar_bytes + old_admin_bytes * 2))
for device in "${!required_by_device[@]}"; do
  capacity_path="${path_by_device[$device]}"
  available_bytes="$(df -PB1 "$capacity_path" | awk 'NR == 2 { print $4 }')"
  [[ "$available_bytes" =~ ^[0-9]+$ ]] || { printf 'available capacity could not be resolved\n' >&2; exit 2; }
  printf 'Bootstrap capacity: path=%s available=%s required=%s\n' \
    "$capacity_path" "$available_bytes" "${required_by_device[$device]}"
  ((available_bytes > required_by_device[$device])) || {
    printf 'insufficient capacity for immutable UAT baseline and data migration\n' >&2
    exit 2
  }
done

cleanup_stage() { rm -rf --one-file-system "$stage" 2>/dev/null || true; }
rollback_baseline() {
  local code=$? current_target="" target
  trap - EXIT ERR INT TERM
  set +e
  systemctl disable --now joysong@uat.service >/dev/null 2>&1 || true
  if [[ -L /opt/joysong/uat/current ]]; then
    current_target="$(readlink -f /opt/joysong/uat/current 2>/dev/null || true)"
    [[ "$current_target" != "$release_root" ]] || rm -f /opt/joysong/uat/current
  fi
  rm -f /opt/joysong/uat/.current.new
  rm -f "$marker_stage"
  cleanup_stage
  if [[ -d "$release_root" && "$backup_owned" == true && -d "$backup_root" ]]; then
    mv "$release_root" "$backup_root/failed-baseline-release"
  fi
  if [[ "$migration_started" == true ]]; then
    for target in /var/lib/joysong/uat/uploads /var/lib/joysong/uat/private /var/lib/joysong/uat/upload-staging; do
      if [[ -d "$target" && ! -L "$target" ]]; then
        find "$target" -mindepth 1 -xdev -delete
      fi
    done
  fi
  if [[ "$backup_owned" == true && -d "$backup_root" ]]; then
    printf 'FAILED_AT=%s\nEXIT_CODE=%s\n' "$(date -u +%Y%m%dT%H%M%SZ)" "$code" >"$backup_root/BASELINE_FAILED"
  fi
  printf 'UAT baseline failed; managed UAT and partial target data were cleaned, and safe old-service recovery was attempted\n' >&2
  exit "$code"
}
trap rollback_baseline EXIT ERR INT TERM

[[ ! -e "$backup_root" ]] || { printf 'immutable baseline backup path already exists\n' >&2; exit 2; }
install -d -o root -g root -m 0700 "$backup_root"
backup_owned=true
install -o root -g root -m 0600 "$old_env" "$backup_root/old-joysong.env"
install -o root -g root -m 0600 "$new_env" "$backup_root/new-joysong.env"
install -o root -g root -m 0600 "$database_backup" "$backup_root/database.sql.gz"
gzip -t "$backup_root/database.sql.gz"
install -o root -g root -m 0600 "$old_jar" "$backup_root/old-server.jar"
tar -czf "$backup_root/old-admin.tar.gz" -C "$(dirname "$old_admin")" -- "$(basename "$old_admin")"
systemctl cat "$old_service" >"$backup_root/old-service.txt"
systemctl status "$old_service" --no-pager >"$backup_root/old-service-status.txt" || true
nginx -T >"$backup_root/nginx.txt" 2>&1
tar -czf "$backup_root/nginx-config.tar.gz" -C /etc nginx
(cd "$backup_root" && sha256sum \
  old-server.jar old-admin.tar.gz database.sql.gz old-joysong.env new-joysong.env nginx-config.tar.gz \
  >SHA256SUMS && sha256sum --check --status SHA256SUMS)
install -d -o root -g root -m 0700 "$backup_root/data"
rsync -ax --numeric-ids "$old_uploads/" "$backup_root/data/uploads/"
migration_started=true
rsync -ax --ignore-existing --chown="joysong-uat:$web_group" \
  "$old_uploads/" /var/lib/joysong/uat/uploads/
chown -R "joysong-uat:$web_group" /var/lib/joysong/uat/uploads
find /var/lib/joysong/uat/uploads -type d -exec chmod 2750 {} +
find /var/lib/joysong/uat/uploads -type f -exec chmod 0640 {} +

if [[ -n "$old_private" ]]; then
  printf 'Migrating persistent private files from %s\n' "$old_private" | tee -a "$backup_root/data-migration.txt"
  rsync -ax --numeric-ids "$old_private/" "$backup_root/data/private/"
  rsync -ax --ignore-existing --chown=joysong-uat:joysong-uat \
    "$old_private/" /var/lib/joysong/uat/private/
else
  printf 'Optional old private directory is absent; the new private directory remains unchanged.\n' |
    tee -a "$backup_root/data-migration.txt"
fi

if [[ -n "$old_staging" ]]; then
  printf 'Migrating persistent staging files from %s\n' "$old_staging" | tee -a "$backup_root/data-migration.txt"
  rsync -ax --numeric-ids "$old_staging/" "$backup_root/data/upload-staging/"
  rsync -ax --ignore-existing --chown=joysong-uat:joysong-uat \
    "$old_staging/" /var/lib/joysong/uat/upload-staging/
else
  printf 'Optional old staging directory is absent; the new upload-staging directory remains unchanged.\n' |
    tee -a "$backup_root/data-migration.txt"
fi
find /var/lib/joysong/uat/private /var/lib/joysong/uat/upload-staging \
  -type d -exec chmod 0700 {} +
find /var/lib/joysong/uat/private /var/lib/joysong/uat/upload-staging \
  -type f -exec chmod 0600 {} +
chown -R joysong-uat:joysong-uat \
  /var/lib/joysong/uat/private /var/lib/joysong/uat/upload-staging

install -d -o root -g joysong-uat-release -m 0550 "$stage"
install -o root -g joysong-uat-release -m 0440 "$old_jar" "$stage/server.jar"
cp -a "$old_admin" "$stage/admin"
tar -czf "$stage/admin.tar.gz" -C "$stage" admin

python3 - "$stage" "$commit" <<'PY'
import hashlib
import json
import pathlib
import sys
import zipfile

root = pathlib.Path(sys.argv[1])
commit = sys.argv[2]

def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()

def migration_digest(path):
    value = hashlib.sha256()
    with zipfile.ZipFile(path) as jar:
        names = sorted(
            info.filename
            for info in jar.infolist()
            if not info.is_dir() and "/db/migration/" in f"/{info.filename}"
        )
        if len(names) != len(set(names)):
            raise SystemExit("old JAR contains duplicate database migration entries")
        for name in names:
            value.update(name.encode("utf-8"))
            value.update(b"\0")
            value.update(hashlib.sha256(jar.read(name)).digest())
    return value.hexdigest()

identity = {
    "schemaVersion": 1,
    "tag": "v0.0.0-uat.0",
    "commit": commit,
    "environment": "uat",
    "buildNumber": 1,
    "apiBaseUrl": "https://api.joyingsong.net",
    "adminBaseUrl": "https://joyingsong.net",
    "databaseMigrationsSha256": migration_digest(root / "server.jar"),
}
(root / "release.json").write_text(
    json.dumps(identity, sort_keys=True) + "\n", encoding="utf-8"
)
manifest = {
    **identity,
    "artifacts": [
        {"name": "server.jar", "sha256": digest(root / "server.jar")},
        {"name": "admin.tar.gz", "sha256": digest(root / "admin.tar.gz")},
        {"name": "release.json", "sha256": digest(root / "release.json")},
    ],
}
(root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
PY

chown -R root:joysong-uat-release "$stage"
find "$stage" -type d -exec chmod 0550 {} +
find "$stage" -type f -exec chmod 0440 {} +
mv "$stage" "$release_root"
ln -s "$release_root" /opt/joysong/uat/.current.new
mv -Tf /opt/joysong/uat/.current.new /opt/joysong/uat/current

# Do not start the managed baseline here. The application has startup and
# scheduled writers, so even a short parallel validation can mutate the shared
# database or copied file trees. The final cutover performs its health check
# only after the old writer is frozen and the authoritative data delta is done.
/usr/local/lib/joysong/deploy-release.sh verify-stored uat "$tag"

cat >"$marker_stage" <<EOF
BASELINE_CAPTURED=true
BASELINE_TAG=$tag
BASELINE_COMMIT=$commit
DATABASE_IDENTITY_SHA256=$database_identity_sha
BACKUP_ROOT=$backup_root
OLD_SERVICE=$old_service
OLD_JAR=$old_jar
OLD_JAR_SHA256=$old_jar_sha256
OLD_ENV=$old_env
OLD_DATA_ROOT=$old_data_root
EOF
chown root:root "$marker_stage"
chmod 0600 "$marker_stage"
mv -Tf "$marker_stage" /etc/joysong/uat/BASELINE_CAPTURED
trap - EXIT ERR INT TERM

printf 'Managed UAT baseline passed offline integrity checks and remains disabled until cutover.\n'
printf 'Old service %s remains the only running application writer.\n' "$old_service"
printf 'Baseline backup: %s\n' "$backup_root"
printf 'Run the explicit final cutover only after DNS/TLS are ready; do not switch Nginx manually.\n'
