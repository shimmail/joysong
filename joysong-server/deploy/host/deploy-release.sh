#!/usr/bin/env bash
set -Eeuo pipefail
umask 027

readonly RETAIN_RELEASES=5
readonly HEALTH_RETRIES=30
readonly HEALTH_INTERVAL_SECONDS=2

fail() {
  printf 'release operation failed: %s\n' "$*" >&2
  exit 2
}

systemctl_property_value() {
  local unit="$1" property="$2" line
  line="$(systemctl show -p "$property" "$unit" 2>/dev/null || true)"
  line="${line%%$'\n'*}"
  [[ "$line" == "${property}="* ]] || return 0
  printf '%s' "${line#*=}"
}

validate_environment_and_tag() {
  local environment="$1" tag="$2"
  [[ "$environment" == "uat" || "$environment" == "prod" ]] || fail "unsupported environment"
  if [[ "$environment" == "uat" ]]; then
    [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+-uat\.[0-9]+$ ]] || fail "invalid UAT tag"
  else
    [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "invalid production tag"
  fi
}

read_deploy_value() {
  local key="$1" file="/etc/joysong/deploy.env" line value
  [[ -r "$file" ]] || fail "missing $file"
  line="$(grep -E "^[[:space:]]*${key}=" "$file" | tail -n 1 || true)"
  [[ -n "$line" ]] || fail "$key is missing from $file"
  value="${line#*=}"
  value="${value%$'\r'}"
  value="${value%\"}"
  value="${value#\"}"
  [[ -n "$value" ]] || fail "$key is empty in $file"
  printf '%s' "$value"
}

read_strict_assignment() {
  local file="$1" key="$2" line_count value
  line_count="$(grep -Ec "^${key}=" "$file" || true)"
  [[ "$line_count" == "1" ]] || fail "$file must contain exactly one $key assignment"
  value="$(sed -n "s/^${key}=//p" "$file")"
  [[ -n "$value" && "$value" != *$'\r'* && "$value" != *$'\n'* ]] || fail "$key is empty or unsafe"
  printf '%s' "$value"
}

validate_prod_rds_binding() {
  local expected_instance_id="${1:-}" expected_host="${2:-}"
  local file=/etc/joysong/prod/rds-binding.env instance_id connection_host
  [[ -f "$file" && ! -L "$file" ]] || fail "production RDS binding file is missing or unsafe"
  [[ "$(stat -c '%U:%G:%a' "$file")" == "root:root:600" ]] ||
    fail "production RDS binding file must be root:root mode 0600"
  [[ "$(grep -Ecv '^(RDS_INSTANCE_ID|RDS_CONNECTION_HOST)=[^[:space:]]+$|^[[:space:]]*$|^[[:space:]]*#' "$file" || true)" == "0" ]] ||
    fail "production RDS binding file contains unsupported content"
  instance_id="$(read_strict_assignment "$file" RDS_INSTANCE_ID)"
  connection_host="$(read_strict_assignment "$file" RDS_CONNECTION_HOST)"
  [[ "$instance_id" =~ ^rm-[A-Za-z0-9]+$ ]] || fail "production RDS instance ID is invalid"
  [[ "$connection_host" =~ ^[a-z0-9][a-z0-9.-]*\.rds\.aliyuncs\.com$ ]] ||
    fail "production RDS connection host is invalid"
  [[ -z "$expected_instance_id" || "$instance_id" == "$expected_instance_id" ]] ||
    fail "ECS RDS instance binding does not match the CI-selected instance"
  [[ -z "$expected_host" || "$connection_host" == "$expected_host" ]] ||
    fail "ECS RDS host binding does not match the current RDS internal endpoint"
  /usr/local/lib/joysong/validate-runtime-config.sh \
    prod /etc/joysong/prod/joysong.env "$connection_host"
  printf 'Production RDS binding valid: instance=%s host=%s\n' "$instance_id" "$connection_host"
}

validate_prod_deploy_sentinel() {
  local file=/etc/joysong/prod/DEPLOY_ENABLED enabled
  [[ -f "$file" && ! -L "$file" ]] || fail "production deployment sentinel is missing or unsafe"
  [[ "$(stat -c '%U:%G:%a' "$file")" == "root:root:600" ]] ||
    fail "production deployment sentinel must be root:root mode 0600"
  [[ "$(grep -Ecv '^PROD_DEPLOY_ENABLED=true$|^[[:space:]]*$|^[[:space:]]*#' "$file" || true)" == "0" ]] ||
    fail "production deployment sentinel contains unsupported content"
  enabled="$(read_strict_assignment "$file" PROD_DEPLOY_ENABLED)"
  [[ "$enabled" == "true" ]] || fail "production deployment sentinel is not enabled"
}

validate_service_start() {
  local environment="$1" maintenance_file authorization_file
  case "$environment" in
    uat)
      /usr/local/lib/joysong/validate-runtime-config.sh \
        uat /etc/joysong/uat/joysong.env
      ;;
    prod)
      validate_prod_rds_binding
      validate_prod_deploy_sentinel
      ;;
    *) fail "unsupported service environment" ;;
  esac
  maintenance_file="/var/lib/joysong-maintenance/$environment"
  authorization_file="/run/joysong-${environment}-start-authorized"
  if [[ -e "$maintenance_file" ]]; then
    [[ -f "$maintenance_file" && ! -L "$maintenance_file" &&
       "$(stat -c '%U:%G:%a' "$maintenance_file")" == "root:root:600" ]] ||
      fail "persistent deployment-maintenance marker is unsafe"
    [[ -f "$authorization_file" && ! -L "$authorization_file" &&
       "$(stat -c '%U:%G:%a' "$authorization_file")" == "root:root:600" ]] ||
      fail "an incomplete deployment blocks automatic service startup"
    rm -f "$authorization_file"
  fi
  printf 'Service start preflight succeeded: environment=%s\n' "$environment"
}

authorize_service_start() {
  local environment="$1"
  install -o root -g root -m 0600 /dev/null "/run/joysong-${environment}-start-authorized"
}

restart_service_authorized() {
  local environment="$1" authorization_file
  authorization_file="/run/joysong-${environment}-start-authorized"
  authorize_service_start "$environment"
  if systemctl restart "joysong@${environment}.service"; then
    rm -f "$authorization_file"
    return 0
  fi
  rm -f "$authorization_file"
  return 1
}

set_deployment_maintenance() {
  local environment="$1" directory=/var/lib/joysong-maintenance file
  file="$directory/$environment"
  [[ -d "$directory" && ! -L "$directory" &&
     "$(stat -c '%U:%G:%a' "$directory")" == "root:root:755" ]] ||
    fail "persistent deployment-maintenance directory is unsafe"
  if [[ -e "$file" ]]; then
    [[ -f "$file" && ! -L "$file" && "$(stat -c '%U:%G' "$file")" == "root:root" ]] ||
      fail "persistent deployment-maintenance marker is unsafe"
  fi
  install -o root -g root -m 0600 /dev/null "$file"
}

require_no_incomplete_transaction() {
  local environment="$1" marker="/var/lib/joysong-maintenance/$1"
  [[ ! -e "$marker" && ! -L "$marker" ]] ||
    fail "an incomplete $environment deployment transaction requires explicit operator recovery"
}

validate_url() {
  local url="$1" expected_host actual_host
  [[ "$url" == https://* ]] || fail "release URL must use HTTPS"
  actual_host="${url#https://}"
  actual_host="${actual_host%%/*}"
  [[ "$actual_host" != *:* && "$actual_host" != *'@'* ]] || fail "release URL host is invalid"
  expected_host="$(read_deploy_value RELEASE_URL_HOST)"
  [[ "$actual_host" == "$expected_host" ]] || fail "release URL host is not allow-listed"
}

atomic_link() {
  local target="$1" link="$2" temporary
  temporary="${link}.new.$$"
  ln -s "$target" "$temporary"
  mv -Tf "$temporary" "$link"
}

clear_previous_binding() {
  local root="$1"
  rm -f "$root/previous" "$root/previous-for"
}

bind_previous() {
  local root="$1" current_tag="$2" previous_target="$3" binding_stage
  [[ "$current_tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-uat\.[0-9]+)?$ ]] ||
    fail "previous binding current tag is invalid"
  [[ "$previous_target" == "$root/releases/"* && -d "$previous_target" ]] ||
    fail "previous binding target is unsafe"
  atomic_link "$previous_target" "$root/previous"
  binding_stage="$root/.previous-for.$$"
  printf '%s\n' "$current_tag" >"$binding_stage"
  chown root:root "$binding_stage"
  chmod 0440 "$binding_stage"
  mv -Tf "$binding_stage" "$root/previous-for"
}

previous_is_bound_to() {
  local root="$1" current_tag="$2"
  [[ -f "$root/previous-for" && ! -L "$root/previous-for" ]] || return 1
  [[ "$(stat -c '%U:%G:%a' "$root/previous-for")" == "root:root:440" ]] || return 1
  [[ "$(cat "$root/previous-for")" == "$current_tag" ]]
}

check_bundle_paths() {
  local archive="$1"
  python3 - "$archive" <<'PY'
import pathlib
import sys
import tarfile

archive = pathlib.Path(sys.argv[1])
with tarfile.open(archive, "r:gz") as bundle:
    for member in bundle.getmembers():
        path = pathlib.PurePosixPath(member.name)
        if path.is_absolute() or ".." in path.parts or "\\" in member.name:
            raise SystemExit("unsafe archive path")
        if member.issym() or member.islnk() or member.isdev() or member.isfifo():
            raise SystemExit("archive links and special files are forbidden")
PY
}

verify_release() {
  local manifest="$1" environment="$2" tag="$3" commit="$4"
  local release_root
  release_root="$(dirname "$manifest")"
  python3 - "$manifest" "$environment" "$tag" "$commit" "$release_root" <<'PY'
import hashlib
import json
import pathlib
import sys
import tarfile
import tempfile
import zipfile
from urllib.parse import urlsplit

path, environment, tag, commit, release_root_arg = sys.argv[1:]
release_root = pathlib.Path(release_root_arg).resolve()
data = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
expected = {"environment": environment, "tag": tag, "commit": commit}
for key, value in expected.items():
    if data.get(key) != value:
        raise SystemExit(f"manifest {key} mismatch")
if data.get("schemaVersion") != 1:
    raise SystemExit("unsupported manifest schema")
if not isinstance(data.get("buildNumber"), int) or data["buildNumber"] < 1:
    raise SystemExit("invalid manifest buildNumber")
migration_digest = data.get("databaseMigrationsSha256")
if not isinstance(migration_digest, str) or len(migration_digest) != 64 or any(c not in "0123456789abcdef" for c in migration_digest):
    raise SystemExit("invalid database migration digest")
api_url = data.get("apiBaseUrl")
admin_url = data.get("adminBaseUrl")
allowed_origins = (
    {("https://api.joyingsong.net", "https://joyingsong.net"),
     ("https://api-uat.joyingsong.net", "https://uat.joyingsong.net")}
    if environment == "uat"
    else {("https://api.joyingsong.net", "https://joyingsong.net")}
)
if (api_url, admin_url) not in allowed_origins:
    raise SystemExit("release API/Admin origin pair is invalid")
baseline_source_commits = data.get("baselineSourceCommits")
if tag == "v0.0.0-uat.0":
    if not isinstance(baseline_source_commits, dict) or set(baseline_source_commits) != {"server", "admin"}:
        raise SystemExit("baseline manifest source commits are missing or malformed")
    if any(
        not isinstance(value, str)
        or len(value) != 40
        or any(character not in "0123456789abcdef" for character in value)
        for value in baseline_source_commits.values()
    ):
        raise SystemExit("baseline manifest source commit is invalid")
    if baseline_source_commits["server"] != commit:
        raise SystemExit("baseline server source commit does not match manifest commit")
elif "baselineSourceCommits" in data:
    raise SystemExit("baseline source commits are forbidden for a regular release")
artifacts = data.get("artifacts")
if not isinstance(artifacts, list):
    raise SystemExit("manifest artifacts must be a list")
artifact_hashes = {
    item.get("name"): item.get("sha256")
    for item in artifacts
    if isinstance(item, dict)
}
if len(artifact_hashes) != len(artifacts):
    raise SystemExit("manifest contains duplicate or malformed artifacts")

def digest(file_path: pathlib.Path) -> str:
    value = hashlib.sha256()
    with file_path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()

for name in ("server.jar", "admin.tar.gz", "release.json"):
    candidate = release_root / name
    expected_hash = artifact_hashes.get(name)
    if not candidate.is_file() or not isinstance(expected_hash, str):
        raise SystemExit(f"missing stored artifact: {name}")
    if digest(candidate) != expected_hash:
        raise SystemExit(f"stored artifact hash mismatch: {name}")

computed_migrations = hashlib.sha256()
with zipfile.ZipFile(release_root / "server.jar") as jar:
    migration_names = sorted(
        info.filename
        for info in jar.infolist()
        if not info.is_dir() and "/db/migration/" in f"/{info.filename}"
    )
    if len(migration_names) != len(set(migration_names)):
        raise SystemExit("server JAR has duplicate database migration entries")
    for name in migration_names:
        computed_migrations.update(name.encode("utf-8"))
        computed_migrations.update(b"\0")
        computed_migrations.update(hashlib.sha256(jar.read(name)).digest())
if computed_migrations.hexdigest() != migration_digest:
    raise SystemExit("server JAR database migration digest mismatch")

release_identity = json.loads((release_root / "release.json").read_text(encoding="utf-8"))
for key, value in {
    **expected,
    "schemaVersion": 1,
    "buildNumber": data["buildNumber"],
    "apiBaseUrl": api_url,
    "adminBaseUrl": admin_url,
    "databaseMigrationsSha256": migration_digest,
}.items():
    if release_identity.get(key) != value:
        raise SystemExit(f"release identity {key} mismatch")
if tag == "v0.0.0-uat.0":
    if release_identity.get("baselineSourceCommits") != baseline_source_commits:
        raise SystemExit("baseline source commits differ between manifest and release identity")
elif "baselineSourceCommits" in release_identity:
    raise SystemExit("regular release identity contains baseline source commits")

def tree_digest(root: pathlib.Path) -> str:
    value = hashlib.sha256()
    for candidate in sorted(root.rglob("*"), key=lambda item: item.relative_to(root).as_posix()):
        relative = candidate.relative_to(root).as_posix()
        if candidate.is_symlink() or not (candidate.is_dir() or candidate.is_file()):
            raise SystemExit(f"unsafe deployed admin entry: {relative}")
        value.update(("d:" if candidate.is_dir() else "f:").encode())
        value.update(relative.encode())
        value.update(b"\0")
        if candidate.is_file():
            value.update(digest(candidate).encode())
    return value.hexdigest()

with tempfile.TemporaryDirectory() as temporary:
    temporary_root = pathlib.Path(temporary)
    with tarfile.open(release_root / "admin.tar.gz", "r:gz") as admin_archive:
        for member in admin_archive.getmembers():
            member_path = pathlib.PurePosixPath(member.name)
            if member_path.is_absolute() or ".." in member_path.parts:
                raise SystemExit("unsafe stored admin archive path")
            if member.issym() or member.islnk() or member.isdev() or member.isfifo():
                raise SystemExit("unsafe stored admin archive type")
        admin_archive.extractall(temporary_root)
    if tree_digest(temporary_root / "admin") != tree_digest(release_root / "admin"):
        raise SystemExit("deployed admin content does not match stored admin archive")
PY
}

migrations_match() {
  local first="$1" second="$2"
  python3 - "$first/manifest.json" "$second/manifest.json" <<'PY'
import json
import pathlib
import sys

digests = [
    json.loads(pathlib.Path(path).read_text(encoding="utf-8")).get("databaseMigrationsSha256")
    for path in sys.argv[1:]
]
if not all(isinstance(value, str) and len(value) == 64 for value in digests):
    raise SystemExit(1)
raise SystemExit(0 if digests[0] == digests[1] else 1)
PY
}

require_restore_drill() {
  local environment="$1" from_target="$2" to_target="$3"
  local file="/etc/joysong/$environment/RESTORE_DRILL_VERIFIED"
  local from_digest to_digest verified marker_environment
  [[ -f "$file" && ! -L "$file" ]] ||
    fail "database migration change requires a root-approved RESTORE_DRILL_VERIFIED sentinel"
  [[ "$(stat -c '%U:%G:%a' "$file")" == "root:root:600" ]] ||
    fail "restore-drill sentinel must be root:root mode 0600"
  [[ "$(grep -Ecv '^(RESTORE_DRILL_VERIFIED|ENVIRONMENT|FROM_MIGRATIONS_SHA256|TO_MIGRATIONS_SHA256)=[^[:space:]]+$|^[[:space:]]*$|^[[:space:]]*#' "$file" || true)" == "0" ]] ||
    fail "restore-drill sentinel contains unsupported content"
  verified="$(read_strict_assignment "$file" RESTORE_DRILL_VERIFIED)"
  marker_environment="$(read_strict_assignment "$file" ENVIRONMENT)"
  from_digest="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["databaseMigrationsSha256"])' "$from_target/release.json")"
  to_digest="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["databaseMigrationsSha256"])' "$to_target/release.json")"
  [[ "$verified" == true && "$marker_environment" == "$environment" ]] ||
    fail "restore-drill sentinel is not enabled for this environment"
  [[ "$(read_strict_assignment "$file" FROM_MIGRATIONS_SHA256)" == "$from_digest" &&
     "$(read_strict_assignment "$file" TO_MIGRATIONS_SHA256)" == "$to_digest" ]] ||
    fail "restore-drill sentinel does not match this exact migration transition"
  printf 'Restore-drill gate verified: environment=%s from=%s to=%s\n' \
    "$environment" "$from_digest" "$to_digest"
}

wait_for_service_health() {
  local environment="$1" port="$2" attempt pid cmdline resolved_jar
  for ((attempt = 1; attempt <= HEALTH_RETRIES; attempt++)); do
    if systemctl is-active --quiet "joysong@${environment}.service"; then
      pid="$(systemctl_property_value "joysong@${environment}.service" MainPID)"
      resolved_jar="$(readlink -f "/opt/joysong/$environment/current/server.jar" 2>/dev/null || true)"
      if [[ "$pid" =~ ^[1-9][0-9]*$ && "$pid" != "1" && -r "/proc/$pid/cmdline" && -n "$resolved_jar" ]]; then
        cmdline="$(tr '\0' '\n' <"/proc/$pid/cmdline" 2>/dev/null || true)"
        if { grep -Fxq "/opt/joysong/$environment/current/server.jar" <<<"$cmdline" ||
             grep -Fxq "$resolved_jar" <<<"$cmdline"; } &&
           curl --fail --silent --show-error --max-time 3 \
             "http://127.0.0.1:${port}/actuator/health" >/dev/null; then
          return 0
        fi
      fi
    fi
    sleep "$HEALTH_INTERVAL_SECONDS"
  done
  return 1
}

verify_existing_target() {
  local environment="$1" target="$2" root tag commit
  root="/opt/joysong/$environment"
  [[ "$target" == "$root/releases/"* && -d "$target" && -f "$target/manifest.json" ]] || return 1
  tag="$(basename "$target")"
  if [[ "$environment" == "uat" ]]; then
    [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+-uat\.[0-9]+$ ]] || return 1
  else
    [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || return 1
  fi
  commit="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["commit"])' \
    "$target/manifest.json")" || return 1
  [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || return 1
  verify_release "$target/manifest.json" "$environment" "$tag" "$commit"
}

restore_existing_target() {
  local environment="$1" target="$2" port="$3" root
  root="/opt/joysong/$environment"
  verify_existing_target "$environment" "$target" || return 1
  atomic_link "$target" "$root/current" || return 1
  systemctl enable "joysong@${environment}.service" || return 1
  restart_service_authorized "$environment" || return 1
  wait_for_service_health "$environment" "$port"
}

require_current_service_or_free_port() {
  local environment="$1" port="$2" root current_target listeners
  root="/opt/joysong/$environment"
  if [[ -L "$root/current" ]]; then
    current_target="$(readlink -f "$root/current")"
    verify_existing_target "$environment" "$current_target" ||
      fail "current release content is not verified"
    wait_for_service_health "$environment" "$port" ||
      fail "current systemd service is not healthy or does not run current/server.jar"
  else
    listeners="$(ss -H -ltn | awk -v suffix=":$port" '$4 ~ (suffix "$") { print }')"
    [[ -z "$listeners" ]] || fail "port $port is already owned outside the managed $environment service"
  fi
}

cleanup_old_releases() {
  local releases_root="$1" current_target="$2" previous_target="$3"
  local -a releases
  local path deleted index failed_public_target=""
  [[ -L "$(dirname "$releases_root")/failed-public-check" ]] &&
    failed_public_target="$(readlink -f "$(dirname "$releases_root")/failed-public-check" 2>/dev/null || true)"
  while true; do
    mapfile -t releases < <(
    find "$releases_root" -mindepth 1 -maxdepth 1 -type d ! -name '.stage-*' ! -name '.download.*' \
        -printf '%T@ %p\n' | sort -nr | cut -d' ' -f2-
    )
    ((${#releases[@]} > RETAIN_RELEASES)) || break
    deleted=false
    for ((index = ${#releases[@]} - 1; index >= 0; index--)); do
      path="${releases[$index]}"
      if [[ "$path" != "$current_target" && "$path" != "$previous_target" && "$path" != "$failed_public_target" ]]; then
        rm -rf --one-file-system "$path"
        deleted=true
        break
      fi
    done
    [[ "$deleted" == true ]] || break
  done
}

deploy_release() {
  local environment="$1" tag="$2" commit="$3" release_url="$4" expected_sha="$5"
  local root releases_root target stage archive old_current="" current_target previous_target="" from_tag
  local port maintenance_file temp_root archive_blocks available_blocks
  local restore_drill_required=false

  validate_environment_and_tag "$environment" "$tag"
  [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fail "commit must be a full lowercase Git SHA"
  [[ "$expected_sha" =~ ^[0-9a-f]{64}$ ]] || fail "expected SHA-256 is invalid"
  validate_url "$release_url"

  root="/opt/joysong/$environment"
  releases_root="$root/releases"
  target="$releases_root/$tag"
  maintenance_file="/var/lib/joysong-maintenance/${environment}"
  port="$([[ "$environment" == "uat" ]] && printf 8081 || printf 8080)"
  [[ ! -e "$target" ]] || fail "release tag is immutable and already exists"

  if [[ "$environment" == "prod" ]]; then
    validate_prod_rds_binding
  else
    /usr/local/lib/joysong/validate-runtime-config.sh \
      "$environment" "/etc/joysong/$environment/joysong.env"
  fi
  if [[ "$environment" == "uat" ]]; then
    [[ -f /etc/joysong/uat/BASELINE_CAPTURED ]] || fail "UAT baseline capture gate is absent"
    grep -Eq '^BASELINE_CAPTURED=true$' /etc/joysong/uat/BASELINE_CAPTURED ||
      fail "UAT baseline capture gate is not enabled"
    [[ -f /etc/joysong/uat/CUTOVER_COMPLETED ]] || fail "UAT final cutover gate is absent"
    grep -Eq '^CUTOVER_COMPLETED=true$' /etc/joysong/uat/CUTOVER_COMPLETED ||
      fail "UAT final cutover gate is not enabled"
  else
    validate_prod_deploy_sentinel
  fi
  # A prior fail-closed transaction may have deliberately withdrawn current.
  # Never let a later ordinary deployment treat that state as a first release
  # and thereby bypass the exact migration restore-drill transition.
  require_no_incomplete_transaction "$environment"
  require_current_service_or_free_port "$environment" "$port"

  temp_root="$(mktemp -d "$releases_root/.download.XXXXXX")"
  archive="$temp_root/release.tgz"
  stage="$releases_root/.stage-${tag}.$$"
  cleanup_temporary_files() {
    rm -rf --one-file-system "$temp_root" "$stage" 2>/dev/null || true
  }
  trap cleanup_temporary_files EXIT

  curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
    --retry 3 --retry-all-errors --connect-timeout 10 --max-time 600 \
    --output "$archive" "$release_url"
  printf '%s  %s\n' "$expected_sha" "$archive" | sha256sum --check --status ||
    fail "release bundle checksum mismatch"

  archive_blocks="$(du -k "$archive" | awk '{print $1}')"
  available_blocks="$(df -Pk "$releases_root" | awk 'NR == 2 {print $4}')"
  ((available_blocks > archive_blocks * 3 + 262144)) || fail "insufficient disk space"

  check_bundle_paths "$archive"
  mkdir -m 0750 "$stage"
  tar --extract --gzip --file "$archive" --directory "$stage" \
    --no-same-owner --no-same-permissions
  [[ -f "$stage/server.jar" ]] || fail "bundle is missing server.jar"
  [[ -f "$stage/admin.tar.gz" ]] || fail "bundle is missing admin.tar.gz"
  [[ -f "$stage/admin/index.html" ]] || fail "bundle is missing admin/index.html"
  [[ -f "$stage/manifest.json" ]] || fail "bundle is missing manifest.json"
  [[ -f "$stage/release.json" ]] || fail "bundle is missing release.json"
  verify_release "$stage/manifest.json" "$environment" "$tag" "$commit"

  [[ -L "$root/current" ]] && old_current="$(readlink -f "$root/current")"
  from_tag="$(if [[ -n "$old_current" ]]; then basename "$old_current"; else printf none; fi)"
  if [[ -n "$old_current" ]] && ! migrations_match "$old_current" "$stage"; then
    require_restore_drill "$environment" "$old_current" "$stage"
    restore_drill_required=true
  fi

  rollback_on_error() {
    local code=$? recovered=false release_was_active=false active_current="" listeners=""
    trap - ERR INT TERM
    set +e
    active_current="$(readlink -f "$root/current" 2>/dev/null || true)"
    rm -f "$root/current.new.$$"
    if [[ "$active_current" == "$target" ]]; then
      release_was_active=true
      if [[ -n "$old_current" && -d "$old_current" ]] && migrations_match "$old_current" "$target"; then
        if restore_existing_target "$environment" "$old_current" "$port"; then
          recovered=true
        fi
      else
        atomic_link "$target" "$root/failed-public-check" || true
        rm -f "$root/current"
        systemctl disable --now "joysong@${environment}.service"
      fi
      clear_previous_binding "$root"
    elif [[ -n "$old_current" && "$active_current" == "$old_current" ]] &&
         wait_for_service_health "$environment" "$port"; then
      # The release never became current. It is safe to remove the immutable
      # target created by this transaction and preserve the existing rollback
      # binding for the still-running current release.
      rm -rf --one-file-system "$target" 2>/dev/null || true
      recovered=true
    elif [[ -z "$old_current" && -z "$active_current" ]]; then
      listeners="$(ss -H -ltn | awk -v suffix=":$port" '$4 ~ (suffix "$") { print }')"
      if [[ -z "$listeners" ]]; then
        rm -rf --one-file-system "$target" 2>/dev/null || true
        recovered=true
      else
        printf 'CRITICAL: first release failed before switch but its port is occupied; maintenance remains enabled\n' >&2
      fi
    else
      printf 'CRITICAL: original current release is not healthy or changed unexpectedly; maintenance remains enabled\n' >&2
    fi
    rm -rf --one-file-system "$temp_root" "$stage" 2>/dev/null || true
    if [[ "$recovered" == true ]]; then
      rm -f "$maintenance_file"
      if [[ "$release_was_active" == true ]]; then
        printf 'Failed deployment rolled back to verified release: %s\n' "$old_current" >&2
      else
        printf 'Failed deployment before release switch; current release is unchanged\n' >&2
      fi
    else
      systemctl disable --now "joysong@${environment}.service" >/dev/null 2>&1 || true
      printf 'CRITICAL: failed deployment could not restore a healthy verified release; maintenance remains enabled\n' >&2
    fi
    exit "$code"
  }
  trap rollback_on_error ERR INT TERM

  set_deployment_maintenance "$environment"
  if [[ "$environment" == "uat" ]]; then
    /usr/local/lib/joysong/backup-runtime-state.sh uat "$tag" database "$from_tag" "$tag"
  else
    /usr/local/lib/joysong/backup-runtime-state.sh prod "$tag" config-only "$from_tag" "$tag"
  fi

  chown -R "root:joysong-${environment}-release" "$stage"
  find "$stage" -type d -exec chmod 0550 {} +
  find "$stage" -type f -exec chmod 0440 {} +
  mv "$stage" "$target"

  atomic_link "$target" "$root/current"
  systemctl enable "joysong@${environment}.service"
  restart_service_authorized "$environment"
  wait_for_service_health "$environment" "$port"
  if [[ -n "$old_current" && -d "$old_current" ]]; then
    bind_previous "$root" "$tag" "$old_current"
  else
    clear_previous_binding "$root"
  fi
  if [[ "$restore_drill_required" == true ]]; then
    rm -f "/etc/joysong/$environment/RESTORE_DRILL_VERIFIED"
  fi
  trap - ERR INT TERM
  rm -f "$maintenance_file"

  current_target="$(readlink -f "$root/current")"
  [[ -L "$root/previous" ]] && previous_target="$(readlink -f "$root/previous")"
  cleanup_old_releases "$releases_root" "$current_target" "$previous_target"
  rm -rf --one-file-system "$temp_root"
  trap - EXIT
  printf 'Deployment succeeded: environment=%s tag=%s commit=%s\n' "$environment" "$tag" "$commit"
}

rollback_release() {
  local environment="$1" tag="$2" recovery_context="${3:-manual}" root target old_current port maintenance_file commit
  [[ "$recovery_context" == "manual" || "$recovery_context" == "public-failure" ]] ||
    fail "rollback recovery context is invalid"
  validate_environment_and_tag "$environment" "$tag"
  root="/opt/joysong/$environment"
  target="$root/releases/$tag"
  maintenance_file="/var/lib/joysong-maintenance/${environment}"
  port="$([[ "$environment" == "uat" ]] && printf 8081 || printf 8080)"
  [[ -d "$target" && -f "$target/manifest.json" ]] || fail "rollback target is unavailable"
  commit="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["commit"])' \
    "$target/manifest.json")"
  [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fail "rollback manifest commit is invalid"
  verify_release "$target/manifest.json" "$environment" "$tag" "$commit"
  if [[ "$environment" == "prod" ]]; then
    validate_prod_rds_binding
  else
    /usr/local/lib/joysong/validate-runtime-config.sh \
      "$environment" "/etc/joysong/$environment/joysong.env"
  fi
  if [[ "$environment" == "prod" ]]; then
    validate_prod_deploy_sentinel
  fi
  [[ -L "$root/current" ]] || fail "current release link is missing"
  old_current="$(readlink -f "$root/current")"
  [[ "$old_current" != "$target" ]] || fail "rollback target is already current"
  migrations_match "$old_current" "$target" ||
    fail "binary rollback is forbidden because database migrations differ; use an approved database recovery plan"
  rollback_on_error() {
    local code=$? recovered=false
    trap - ERR INT TERM
    set +e
    if [[ "$recovery_context" == "manual" ]] &&
       restore_existing_target "$environment" "$old_current" "$port"; then
      recovered=true
      clear_previous_binding "$root"
      rm -f "$maintenance_file"
      printf 'Failed rollback restored the original verified release: %s\n' "$old_current" >&2
    else
      systemctl disable --now "joysong@${environment}.service" >/dev/null 2>&1 || true
      atomic_link "$old_current" "$root/failed-public-check" >/dev/null 2>&1 || true
      rm -f "$root/current"
      clear_previous_binding "$root"
      printf 'CRITICAL: failed rollback was withdrawn; known-bad release was not restored and maintenance remains enabled\n' >&2
    fi
    exit "$code"
  }
  trap rollback_on_error ERR INT TERM
  set_deployment_maintenance "$environment"
  if [[ "$environment" == "uat" ]]; then
    /usr/local/lib/joysong/backup-runtime-state.sh uat "rollback-$tag" database \
      "$(basename "$old_current")" "$tag"
  else
    /usr/local/lib/joysong/backup-runtime-state.sh prod "rollback-$tag" config-only \
      "$(basename "$old_current")" "$tag"
  fi

  atomic_link "$target" "$root/current"
  systemctl enable "joysong@${environment}.service"
  restart_service_authorized "$environment"
  wait_for_service_health "$environment" "$port"
  if [[ "$recovery_context" == "public-failure" ]]; then
    clear_previous_binding "$root"
  else
    bind_previous "$root" "$tag" "$old_current"
  fi
  trap - ERR INT TERM
  rm -f "$maintenance_file"
  printf 'Rollback succeeded: environment=%s tag=%s commit=%s\n' "$environment" "$tag" "$commit"
}

recover_public_failure() {
  local environment="$1" expected_tag="$2" root current_target previous_target="" port maintenance_file listeners
  validate_environment_and_tag "$environment" "$expected_tag"
  root="/opt/joysong/$environment"
  port="$([[ "$environment" == "uat" ]] && printf 8081 || printf 8080)"
  maintenance_file="/var/lib/joysong-maintenance/${environment}"

  # Fail closed before inspecting state. If state has changed unexpectedly, the
  # proxy remains in maintenance instead of exposing an unverified deployment.
  set_deployment_maintenance "$environment"
  [[ -L "$root/current" ]] || fail "current release link is missing; maintenance remains enabled"
  current_target="$(readlink -f "$root/current")"
  [[ "$(basename "$current_target")" == "$expected_tag" ]] ||
    fail "current release changed after deployment; maintenance remains enabled"
  verify_existing_target "$environment" "$current_target" ||
    fail "failed public-check release is not verifiable; maintenance remains enabled"
  systemctl stop "joysong@${environment}.service"
  systemctl is-active --quiet "joysong@${environment}.service" &&
    fail "failed public-check release could not be stopped; maintenance remains enabled"

  if previous_is_bound_to "$root" "$expected_tag" && [[ -L "$root/previous" ]]; then
    previous_target="$(readlink -f "$root/previous" 2>/dev/null || true)"
  fi
  if [[ -n "$previous_target" && "$previous_target" != "$current_target" ]] &&
     verify_existing_target "$environment" "$previous_target" &&
     migrations_match "$previous_target" "$current_target"; then
    rollback_release "$environment" "$(basename "$previous_target")" public-failure
    return
  fi

  # If there is no migration-compatible previous target, stop the failed
  # release, retain it and an audit link, and leave maintenance enabled until an
  # operator has corrected the route or deployed a replacement.
  systemctl disable --now "joysong@${environment}.service"
  systemctl is-active --quiet "joysong@${environment}.service" &&
    fail "failed first release could not be stopped; maintenance remains enabled"
  atomic_link "$current_target" "$root/failed-public-check"
  rm -f "$root/current"
  listeners="$(ss -H -ltn | awk -v suffix=":$port" '$4 ~ (suffix "$") { print }')"
  [[ -z "$listeners" ]] || fail "failed first release port remains open; maintenance remains enabled"
  printf 'Failed %s release withdrawn safely; retained=%s maintenance=%s\n' \
    "$environment" "$current_target" "$maintenance_file"
}

preflight_environment() {
  local environment="$1" expected_instance_id="${2:-}" expected_host="${3:-}"
  [[ "$environment" == "uat" || "$environment" == "prod" ]] || fail "unsupported environment"
  if [[ "$environment" == "prod" ]]; then
    [[ -n "$expected_instance_id" && -n "$expected_host" ]] ||
      fail "production preflight requires the CI-resolved RDS identity"
    validate_prod_rds_binding "$expected_instance_id" "$expected_host"
  else
    [[ -z "$expected_instance_id" && -z "$expected_host" ]] || fail "UAT preflight has unexpected arguments"
    /usr/local/lib/joysong/validate-runtime-config.sh \
      "$environment" "/etc/joysong/$environment/joysong.env"
  fi
  if [[ "$environment" == "uat" ]]; then
    [[ -f /etc/joysong/uat/BASELINE_CAPTURED ]] || fail "UAT baseline capture gate is absent"
    grep -Eq '^BASELINE_CAPTURED=true$' /etc/joysong/uat/BASELINE_CAPTURED ||
      fail "UAT baseline capture gate is not enabled"
    [[ -f /etc/joysong/uat/CUTOVER_COMPLETED ]] || fail "UAT final cutover gate is absent"
    grep -Eq '^CUTOVER_COMPLETED=true$' /etc/joysong/uat/CUTOVER_COMPLETED ||
      fail "UAT final cutover gate is not enabled"
  else
    validate_prod_deploy_sentinel
  fi
  require_no_incomplete_transaction "$environment"
  port="$([[ "$environment" == "uat" ]] && printf 8081 || printf 8080)"
  require_current_service_or_free_port "$environment" "$port"
  printf 'Preflight succeeded: environment=%s\n' "$environment"
}

verify_uat_isolation() {
  /usr/local/lib/joysong/validate-runtime-config.sh \
    uat /etc/joysong/uat/joysong.env "" \
    https://api-uat.joyingsong.net https://uat.joyingsong.net
  [[ -L /opt/joysong/uat/current ]] || fail "managed UAT current release is missing"
  current_target="$(readlink -f /opt/joysong/uat/current)"
  verify_existing_target uat "$current_target" || fail "managed UAT current release is invalid"
  python3 - "$current_target/release.json" <<'PY'
import json
import pathlib
import sys

identity = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
if identity.get("apiBaseUrl") != "https://api-uat.joyingsong.net":
    raise SystemExit("current UAT release was not built for the isolated API origin")
if identity.get("adminBaseUrl") != "https://uat.joyingsong.net":
    raise SystemExit("current UAT release was not built for the isolated Admin origin")
PY
  printf 'UAT runtime and release are bound to the isolated UAT origins.\n'
}

main() {
  local operation="${1:-}" environment="${2:-}"
  mkdir -p /run/lock
  exec 9>"/run/lock/joysong-${environment:-unknown}-deploy.lock"
  flock -n 9 || fail "another release operation is already active"
  case "$operation" in
    deploy)
      (($# == 6)) || fail "deploy requires environment, tag, commit, URL and SHA-256"
      deploy_release "$2" "$3" "$4" "$5" "$6"
      ;;
    rollback)
      (($# == 3)) || fail "rollback requires environment and tag"
      rollback_release "$2" "$3"
      ;;
    rollback-previous)
      (($# == 2)) || fail "rollback-previous requires environment"
      validate_environment_and_tag "$2" "$(basename "$(readlink -f "/opt/joysong/$2/current" 2>/dev/null || true)")"
      previous_is_bound_to "/opt/joysong/$2" "$(basename "$(readlink -f "/opt/joysong/$2/current")")" ||
        fail "previous release is not bound to the current deployment transaction"
      previous_target="$(readlink -f "/opt/joysong/$2/previous" 2>/dev/null || true)"
      [[ -n "$previous_target" && -d "$previous_target" ]] || fail "previous release is unavailable"
      rollback_release "$2" "$(basename "$previous_target")"
      ;;
    recover-public-failure)
      (($# == 3)) || fail "recover-public-failure requires environment and expected tag"
      recover_public_failure "$2" "$3"
      ;;
    verify-uat-isolation)
      if (($# != 2)) || [[ "$2" != "uat" ]]; then
        fail "verify-uat-isolation requires UAT"
      fi
      verify_uat_isolation
      ;;
    preflight)
      if [[ "$environment" == "prod" ]]; then
        (($# == 4)) || fail "production preflight requires environment, RDS instance ID and host"
        preflight_environment "$2" "$3" "$4"
      else
        (($# == 2)) || fail "UAT preflight requires environment"
        preflight_environment "$2"
      fi
      ;;
    *) fail "unsupported release operation" ;;
  esac
}

if [[ "${1:-}" == "validate-start" ]]; then
  (($# == 2)) || fail "validate-start requires an environment"
  validate_service_start "$2"
  exit 0
fi
if [[ "${1:-}" == "verify-stored" ]]; then
  (($# == 3)) || fail "verify-stored requires an environment and tag"
  validate_environment_and_tag "$2" "$3"
  verify_existing_target "$2" "/opt/joysong/$2/releases/$3" ||
    fail "stored release verification failed"
  printf 'Stored release verified: environment=%s tag=%s\n' "$2" "$3"
  exit 0
fi

main "$@"
