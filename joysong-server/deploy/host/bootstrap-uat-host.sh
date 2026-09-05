#!/bin/bash
set -Eeuo pipefail
umask 077

readonly RUNNER_USER=joysong-gh-runner
readonly APP_USER=joysong-demo
readonly APP_GROUP=joysong-demo
readonly NGINX_USER=www-data
readonly NGINX_GROUP=www-data
readonly RUNNER_HOME=/var/lib/joysong-actions-runner
readonly RUNNER_ROOT=/opt/joysong-actions-runner
readonly REPOSITORY_URL=https://github.com/shimmail/joysong
readonly RUNNER_NAME=joysong-uat-i-bp19abm7697mvhl0xewu
readonly RUNNER_LABEL=joysong-uat-deploy
readonly RUNNER_SERVICE=joysong-uat-runner.service
readonly APP_SERVICE=joysong-demo.service
readonly EXPECTED_RUNNER_VERSION=2.337.0
readonly EXPECTED_RUNNER_SHA256=70920811a4f8ad4328818682bca5c6469c1c942fab52448868071d0063816613
readonly DATABASE_NAME=myapp_worktree_uat

fail() {
  printf 'JoySong UAT host bootstrap failed: %s\n' "$*" >&2
  exit 2
}

# Tests may redirect absolute paths only while running as an unprivileged user.
# Root execution deliberately does not preserve or accept this override.
if [[ -n "${JOYSONG_UAT_BOOTSTRAP_TEST_ROOT:-}" ]]; then
  [[ "${JOYSONG_UAT_BOOTSTRAP_TESTING:-}" == 1 && "$EUID" -ne 0 ]] ||
    fail "test root override is forbidden for root or outside the test harness"
  readonly ROOT_PREFIX="${JOYSONG_UAT_BOOTSTRAP_TEST_ROOT%/}"
  readonly TEST_MODE=true
  PYTHON_BIN="$(command -v python)"
  readonly PYTHON_BIN
else
  [[ "$EUID" -eq 0 ]] || fail "run as root"
  readonly PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
  export PATH
  readonly ROOT_PREFIX=''
  readonly TEST_MODE=false
  readonly PYTHON_BIN=/usr/bin/python3
  cd /
fi

host_path() { printf '%s%s' "$ROOT_PREFIX" "$1"; }

ENV_DIR="$(host_path /etc/joysong-demo)"
readonly ENV_DIR
readonly ENV_FILE="$ENV_DIR/joysong.env"
readonly HOST_CONTRACT="$ENV_DIR/host-contract"
MYSQL_CONFIG="$(host_path /etc/mysql/joysong-uat-backup.cnf)"
SYSTEMD_UNIT="$(host_path "/etc/systemd/system/$APP_SERVICE")"
RUNNER_UNIT="$(host_path "/etc/systemd/system/$RUNNER_SERVICE")"
NGINX_SITE="$(host_path /etc/nginx/conf.d/joysong-public.conf)"
NGINX_DEFAULT="$(host_path /etc/nginx/sites-enabled/default)"
INSTALL_ROOT="$(host_path /usr/local/lib/joysong-deploy)"
ENTRYPOINT="$(host_path /usr/local/sbin/joysong-uat-deploy)"
SUDOERS="$(host_path /etc/sudoers.d/joysong-uat-deploy)"
BACKEND_ROOT="$(host_path /opt/joysong-demo)"
ADMIN_ROOT="$(host_path /var/www/joysong-demo)"
DATA_ROOT="$(host_path /var/lib/joysong-demo)"
DEPLOY_ROOT="$(host_path /var/lib/joysong-deploy)"
RUNNER_HOME_PATH="$(host_path "$RUNNER_HOME")"
RUNNER_ROOT_PATH="$(host_path "$RUNNER_ROOT")"
readonly MYSQL_CONFIG SYSTEMD_UNIT RUNNER_UNIT NGINX_SITE NGINX_DEFAULT INSTALL_ROOT ENTRYPOINT
readonly SUDOERS BACKEND_ROOT ADMIN_ROOT DATA_ROOT DEPLOY_ROOT RUNNER_HOME_PATH RUNNER_ROOT_PATH

mode_not_writable_by_group_or_other() {
  local mode="$1"
  [[ "$mode" =~ ^[0-7]{3,4}$ ]] && (((8#$mode & 8#022) == 0))
}

require_root_path_chain() {
  local cursor="$1" label="$2"
  [[ "$TEST_MODE" == false ]] || return 0
  cursor="$(readlink -f "$cursor" 2>/dev/null || true)"
  [[ "$cursor" == /* ]] || fail "$label path cannot be resolved"
  while [[ "$cursor" != / ]]; do
    [[ "$(stat -c '%U' "$cursor")" == root ]] || fail "$label path is not root-owned: $cursor"
    mode_not_writable_by_group_or_other "$(stat -c '%a' "$cursor")" || fail "$label path is writable by group or other: $cursor"
    cursor="$(dirname "$cursor")"
  done
}

require_regular_file() {
  local path="$1" label="$2"
  [[ -f "$path" && ! -L "$path" ]] || fail "$label must be a regular non-link file"
  [[ "$(stat -c '%h' "$path")" == 1 ]] || fail "$label must have exactly one hard link"
}

require_input_file() {
  local path="$1" label="$2"
  require_regular_file "$path" "$label"
  if [[ "$TEST_MODE" == false ]]; then
    [[ "$(stat -c '%U:%G:%a' "$path")" == root:root:600 ]] ||
      fail "$label must be root:root mode 0600"
  fi
}

require_root_protected_file() {
  local path="$1" mode="$2" label="$3"
  require_regular_file "$path" "$label"
  if [[ "$TEST_MODE" == false ]]; then
    [[ "$(stat -c '%a' "$path")" == "$mode" ]] || fail "$label mode drifted"
    [[ "$(stat -c '%U' "$path")" == root ]] || fail "$label owner drifted"
  fi
}

install_directory() {
  local owner="$1" group="$2" mode="$3" path
  shift 3
  for path in "$@"; do
    if [[ "$TEST_MODE" == true ]]; then
      mkdir -p -- "$path"
      chmod "$mode" "$path"
    else
      install -d -o "$owner" -g "$group" -m "$mode" "$path"
    fi
  done
}

install_file() {
  local owner="$1" group="$2" mode="$3" source="$4" target="$5"
  if [[ "$TEST_MODE" == true ]]; then
    cp -- "$source" "$target"
    chmod "$mode" "$target"
  else
    install -o "$owner" -g "$group" -m "$mode" "$source" "$target"
  fi
}

user_exists() {
  local user="$1"
  if [[ "$TEST_MODE" == true ]]; then
    [[ -f "$(host_path "/run/joysong-bootstrap-users/$user")" ]]
  else
    getent passwd "$user" >/dev/null
  fi
}

ensure_system_user() {
  local user="$1" home="$2" marker
  if user_exists "$user"; then
    if [[ "$TEST_MODE" == false ]]; then
      [[ "$(getent passwd "$user" | cut -d: -f6)" == "$home" ]] || fail "$user home drifted"
      [[ "$(getent passwd "$user" | cut -d: -f7)" =~ /(usr/)?sbin/nologin$ ]] ||
        fail "$user permits interactive login"
      [[ "$(id -nG "$user")" == "$user" ]] || fail "$user has unexpected supplementary groups"
    fi
    return
  fi
  if [[ "$TEST_MODE" == true ]]; then
    marker="$(host_path "/run/joysong-bootstrap-users/$user")"
    install_directory root root 0700 "$(dirname "$marker")"
    printf 'home=%s\nshell=/usr/sbin/nologin\n' "$home" >"$marker"
    chmod 0600 "$marker"
  else
    useradd --system --user-group --home-dir "$home" --create-home --shell /usr/sbin/nologin "$user"
  fi
}

verify_system_user() {
  local user="$1" home="$2"
  user_exists "$user" || fail "$user is missing"
  [[ "$TEST_MODE" == true ]] && return 0
  [[ "$(getent passwd "$user" | cut -d: -f6)" == "$home" ]] || fail "$user home drifted"
  [[ "$(getent passwd "$user" | cut -d: -f7)" =~ /(usr/)?sbin/nologin$ ]] || fail "$user permits interactive login"
  [[ "$(id -nG "$user")" == "$user" ]] || fail "$user has unexpected supplementary groups"
}

verify_nginx_identity() {
  [[ "$TEST_MODE" == true ]] && return 0
  getent passwd "$NGINX_USER" >/dev/null || fail "Ubuntu Nginx user is missing"
  getent group "$NGINX_GROUP" >/dev/null || fail "Ubuntu Nginx group is missing"
  [[ "$(id -gn "$NGINX_USER")" == "$NGINX_GROUP" ]] || fail "Ubuntu Nginx primary group drifted"
}

service_state() {
  local service="$1" property="$2" marker
  if [[ "$TEST_MODE" == true ]]; then
    marker="$(host_path "/run/joysong-bootstrap-services/${service}.${property}")"
    [[ -f "$marker" ]] && cat "$marker" || printf '%s\n' inactive
  elif [[ "$property" == active ]]; then
    systemctl is-active "$service" 2>/dev/null || true
  else
    systemctl is-enabled "$service" 2>/dev/null || true
  fi
}

set_test_service_state() {
  local service="$1" property="$2" value="$3" marker
  marker="$(host_path "/run/joysong-bootstrap-services/${service}.${property}")"
  install_directory root root 0700 "$(dirname "$marker")"
  printf '%s\n' "$value" >"$marker"
}

host_architecture() {
  if [[ "$TEST_MODE" == true && -f "$(host_path /run/joysong-bootstrap-arch)" ]]; then
    cat "$(host_path /run/joysong-bootstrap-arch)"
  else
    uname -m
  fi
}

systemd_version() {
  if [[ "$TEST_MODE" == true && -f "$(host_path /run/joysong-bootstrap-systemd-version)" ]]; then
    cat "$(host_path /run/joysong-bootstrap-systemd-version)"
  else
    systemd --version | sed -n '1s/^systemd \([0-9][0-9]*\).*/\1/p'
  fi
}

port_is_free() {
  local port="$1"
  if [[ "$TEST_MODE" == true ]]; then
    [[ ! -e "$(host_path "/run/joysong-bootstrap-port-$port-busy")" ]]
  else
    if ss -H -ltn "sport = :$port" | grep -q .; then
      return 1
    fi
    return 0
  fi
}

validate_platform() {
  local os_release version link_target
  os_release="$(host_path /etc/os-release)"
  if [[ -L "$os_release" ]]; then
    link_target="$(readlink "$os_release")"
    [[ "$link_target" == ../usr/lib/os-release || "$link_target" == /usr/lib/os-release ]] ||
      fail "operating system release symlink is unexpected"
    os_release="$(host_path /usr/lib/os-release)"
  fi
  require_regular_file "$os_release" "operating system release"
  grep -qx 'ID=ubuntu' "$os_release" || fail "host must be Ubuntu"
  version="$(sed -n 's/^VERSION_ID="\{0,1\}\([^"[:space:]]*\)"\{0,1\}$/\1/p' "$os_release")"
  [[ "$version" == 24.04 ]] || fail "host must be Ubuntu 24.04"
  [[ "$(host_architecture)" == x86_64 ]] || fail "host architecture must be x86_64"
  [[ "$(systemd_version)" == 255 ]] || fail "host must use systemd 255"
  if [[ "$TEST_MODE" == false ]]; then
    [[ -d /run/systemd/system ]] || fail "systemd is not PID 1"
  fi
}

validate_blank_host() {
  local path service listeners address port saw_ssh=false
  for service in "$APP_SERVICE" "$RUNNER_SERVICE"; do
    [[ "$(service_state "$service" active)" != active ]] || fail "$service is already active"
    [[ "$(service_state "$service" enabled)" != enabled ]] || fail "$service is already enabled"
  done
  user_exists "$APP_USER" && fail "$APP_USER already exists without a host contract"
  user_exists "$RUNNER_USER" && fail "$RUNNER_USER already exists without a host contract"
  for path in "$HOST_CONTRACT" "$ENV_FILE" "$MYSQL_CONFIG" "$SYSTEMD_UNIT" "$RUNNER_UNIT" \
    "$NGINX_SITE" "$ENTRYPOINT" "$SUDOERS" "$BACKEND_ROOT" "$ADMIN_ROOT" "$DATA_ROOT" \
    "$DEPLOY_ROOT" "$RUNNER_ROOT_PATH" "$RUNNER_HOME_PATH"; do
    [[ ! -e "$path" && ! -L "$path" ]] || fail "fresh host path already exists: ${path#"$ROOT_PREFIX"}"
  done
  port_is_free 80 || fail "TCP port 80 is already in use"
  port_is_free 3306 || fail "TCP port 3306 is already in use"
  port_is_free 8080 || fail "TCP port 8080 is already in use"
  if [[ "$TEST_MODE" == false ]]; then
    command -v ss >/dev/null || fail "ss is required for the listener preflight"
    listeners="$(ss -H -ltn)" || fail "TCP listeners cannot be inspected"
    while read -r address; do
      [[ -n "$address" ]] || continue
      port="${address##*:}"
      [[ "$port" == 22 ]] || fail "unexpected listening TCP port before bootstrap: $port"
      saw_ssh=true
    done < <(awk '{print $4}' <<<"$listeners")
    [[ "$saw_ssh" == true ]] || fail "SSH listener is missing"
  fi
}

preflight() {
  validate_platform
  validate_blank_host
  local available
  if [[ "$TEST_MODE" == true ]]; then
    available=16777216
  else
    available="$(df -Pk / | awk 'NR==2 {print $4}')"
  fi
  [[ "$available" =~ ^[0-9]+$ && "$available" -ge 8388608 ]] || fail "at least 8 GiB free disk is required"
  printf 'JoySong UAT Ubuntu 24.04 fresh-host preflight passed\n'
}

resolve_sources() {
  local source_dir="$1" source
  if [[ -f "$source_dir/host/joysong-uat-deploy" ]]; then
    DEPLOY_SOURCE="$source_dir/host/joysong-uat-deploy"
    VERIFY_SOURCE="$source_dir/host/verify-uat-release.py"
    SYSTEMD_SOURCE="$source_dir/systemd/joysong-demo.service"
    NGINX_SOURCE="$source_dir/nginx/joysong-public.conf"
  else
    DEPLOY_SOURCE="$source_dir/joysong-uat-deploy"
    VERIFY_SOURCE="$source_dir/verify-uat-release.py"
    SYSTEMD_SOURCE="$source_dir/joysong-demo.service"
    NGINX_SOURCE="$source_dir/joysong-public.conf"
  fi
  for source in "$DEPLOY_SOURCE" "$VERIFY_SOURCE" "$SYSTEMD_SOURCE" "$NGINX_SOURCE"; do
    require_regular_file "$source" "bootstrap source $source"
    mode_not_writable_by_group_or_other "$(stat -c '%a' "$source")" || fail "bootstrap source is writable by group or other"
    if [[ "$TEST_MODE" == false ]]; then
      [[ "$(stat -c '%U' "$source")" == root ]] || fail "bootstrap source must be root-owned"
    fi
  done
}

validate_secrets_directory() {
  local directory="$1" phase="$2"
  [[ -d "$directory" && ! -L "$directory" ]] || fail "secrets directory is unsafe"
  if [[ "$TEST_MODE" == false ]]; then
    [[ "$(stat -c '%U:%G' "$directory")" == root:root ]] || fail "secrets directory must be root-owned"
    mode_not_writable_by_group_or_other "$(stat -c '%a' "$directory")" ||
      fail "secrets directory is writable by group or other"
  fi
  "$PYTHON_BIN" -I - "$directory" "$phase" <<'PY'
import os, stat, sys
directory, phase = sys.argv[1:]
expected = {"joysong.env", "joysong-uat-backup.cnf"}
if phase == "fresh":
    expected.add("runner-registration-token")
actual = set(os.listdir(directory))
if actual != expected:
    raise SystemExit("secrets directory has unexpected or missing members")
for name in actual:
    details = os.lstat(os.path.join(directory, name))
    if not stat.S_ISREG(details.st_mode) or details.st_nlink != 1:
        raise SystemExit("secrets directory member is not one regular inode")
PY
}

validate_environment_file() {
  "$PYTHON_BIN" -I - "$1" "$DATABASE_NAME" <<'PY'
import re, sys
path, expected_database = sys.argv[1:]
values = {}
for number, raw in enumerate(open(path, encoding="utf-8"), 1):
    line = raw.rstrip("\r\n")
    if not line or line.startswith("#"):
        continue
    match = re.fullmatch(r"([A-Z][A-Z0-9_]*)=(.*)", line)
    if not match or match.group(1) in values:
        raise SystemExit("invalid or duplicate environment assignment at line {}".format(number))
    values[match.group(1)] = match.group(2)
required = ("DB_URL", "DB_USERNAME", "DB_PASSWORD", "DEMO_DATABASE_NAME", "JWT_SECRET")
if any(not values.get(key) for key in required):
    raise SystemExit("runtime environment is missing a required value")
if values.get("SPRING_PROFILES_ACTIVE") != "demo":
    raise SystemExit("SPRING_PROFILES_ACTIVE must be demo")
if values.get("SERVER_ADDRESS") != "127.0.0.1" or values.get("SERVER_PORT") != "8080":
    raise SystemExit("backend must bind only to 127.0.0.1:8080")
url = values["DB_URL"]
match = re.match(r"^jdbc:mysql://127\.0\.0\.1(?::3306)?/([A-Za-z][A-Za-z0-9_]{0,63})(?:\?.*)?$", url)
if not match or match.group(1) != expected_database or values["DEMO_DATABASE_NAME"] != expected_database:
    raise SystemExit("runtime database must be local {}".format(expected_database))
if "createdatabaseifnotexist" in url.lower():
    raise SystemExit("DB_URL must not create a database")
if len(values["JWT_SECRET"]) < 32:
    raise SystemExit("JWT_SECRET must contain at least 32 characters")
PY
}

validate_mysql_option_file() {
  "$PYTHON_BIN" -I - "$1" "$2" "$DATABASE_NAME" <<'PY'
import configparser, re, sys
path, environment_path, expected_database = sys.argv[1:]
parser = configparser.RawConfigParser(interpolation=None, strict=True, allow_no_value=False)
try:
    with open(path, encoding="utf-8") as stream:
        parser.read_file(stream)
except (OSError, configparser.Error) as error:
    raise SystemExit("invalid MySQL option file: {}".format(error))
if parser.sections() != ["client"]:
    raise SystemExit("MySQL option file must contain only [client]")
allowed = {"host", "port", "protocol", "user", "password", "database"}
keys = set(parser["client"])
if not keys <= allowed or not {"host", "port", "user", "password", "database"} <= keys:
    raise SystemExit("MySQL option file contains missing or forbidden keys")
section = parser["client"]
if section["host"] != "127.0.0.1" or section["port"] != "3306" or section["database"] != expected_database:
    raise SystemExit("MySQL option file must select the local UAT database")
if section.get("protocol", "TCP").upper() != "TCP":
    raise SystemExit("MySQL option file protocol must be TCP")
if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,31}", section["user"]) or not section["password"]:
    raise SystemExit("MySQL backup credential is invalid")
app_user = None
for raw in open(environment_path, encoding="utf-8"):
    if raw.startswith("DB_USERNAME="):
        app_user = raw.rstrip("\r\n").split("=", 1)[1]
if not app_user or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,31}", app_user):
    raise SystemExit("MySQL application account name is invalid")
if app_user == section["user"]:
    raise SystemExit("application and backup MySQL accounts must be distinct")
PY
}

validate_runner_archive() {
  local archive="$1" version="$2" sha="$3" expected_sha="$EXPECTED_RUNNER_SHA256"
  [[ "$version" == "$EXPECTED_RUNNER_VERSION" ]] || fail "runner version must be $EXPECTED_RUNNER_VERSION"
  require_regular_file "$archive" "runner archive"
  if [[ "$TEST_MODE" == true ]]; then expected_sha="$(sha256sum "$archive" | awk '{print $1}')"; fi
  [[ "$sha" == "$expected_sha" ]] || fail "runner SHA-256 must match the approved release"
  [[ "$(sha256sum "$archive" | awk '{print $1}')" == "$sha" ]] || fail "runner archive SHA-256 mismatch"
  "$PYTHON_BIN" -I - "$archive" <<'PY'
import pathlib, sys, tarfile
members = 0
expanded = 0
with tarfile.open(sys.argv[1], mode="r|gz") as stream:
    for member in stream:
        members += 1
        expanded += member.size
        path = pathlib.PurePosixPath(member.name)
        if members > 20000 or expanded > 2 * 1024 * 1024 * 1024:
            raise SystemExit("runner archive exceeds resource limits")
        if not member.name or path.is_absolute() or ".." in path.parts or "\\" in member.name:
            raise SystemExit("runner archive contains an unsafe path")
        if member.isdev() or member.isfifo() or member.issym() or member.islnk():
            raise SystemExit("runner archive contains a link or special member")
        if not (member.isdir() or member.isfile()):
            raise SystemExit("runner archive contains an unsupported member")
if members == 0:
    raise SystemExit("runner archive is empty")
PY
}

install_packages() {
  if [[ "$TEST_MODE" == true ]]; then
    install_directory root root 0700 "$(host_path /run)"
    printf '%s\n' openjdk-17-jre-headless nginx mysql-server mysql-client python3 curl unzip sudo rsync iproute2 \
      >"$(host_path /run/joysong-bootstrap-packages)"
    return
  fi
  export DEBIAN_FRONTEND=noninteractive
  apt-get update
  apt-get install -y --no-install-recommends openjdk-17-jre-headless nginx mysql-server mysql-client python3 curl unzip sudo rsync iproute2 ca-certificates
  [[ "$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')" == 17 ]] || fail "OpenJDK 17 is unavailable"
  mysql --version | grep -Eq 'Distrib 8\.0\.' || fail "MySQL 8 client is unavailable"
  mysqld --version | grep -Eq 'Ver 8\.0\.' || fail "MySQL 8 server is unavailable"
  systemctl enable --now mysql
}

prepare_nginx_default() {
  [[ -e "$NGINX_DEFAULT" || -L "$NGINX_DEFAULT" ]] || return 0
  [[ -L "$NGINX_DEFAULT" && "$(readlink "$NGINX_DEFAULT")" == /etc/nginx/sites-available/default ]] ||
    fail "unexpected enabled Nginx default site"
  rm -- "$NGINX_DEFAULT"
}

provision_database() {
  if [[ "$TEST_MODE" == true ]]; then
    install_directory root root 0700 "$(host_path /var/lib/mysql)"
    printf '%s\n' "$DATABASE_NAME" >"$(host_path /var/lib/mysql/joysong-bootstrap-database)"
    return
  fi
  local unexpected_schemas unexpected_accounts
  printf 'Resolved database host: 127.0.0.1\nResolved database name: %s\n' "$DATABASE_NAME"
  unexpected_schemas="$(mysql --protocol=socket --user=root --batch --skip-column-names --execute="
    SELECT SCHEMA_NAME FROM information_schema.SCHEMATA
    WHERE SCHEMA_NAME NOT IN ('information_schema','mysql','performance_schema','sys') LIMIT 1;")"
  [[ -z "$unexpected_schemas" ]] || fail "fresh MySQL contains an unexpected non-system database"
  unexpected_accounts="$(mysql --protocol=socket --user=root --batch --skip-column-names --execute="
    SELECT User FROM mysql.user
    WHERE NOT ((User='root' AND Host='localhost') OR User IN ('mysql.infoschema','mysql.session','mysql.sys')) LIMIT 1;")"
  [[ -z "$unexpected_accounts" ]] || fail "fresh MySQL contains an unexpected account"
  "$PYTHON_BIN" -I - "$1" "$2" "$DATABASE_NAME" <<'PY' | mysql --protocol=socket --user=root
import configparser, re, sys
environment_path, backup_path, database = sys.argv[1:]
values = {}
for raw in open(environment_path, encoding="utf-8"):
    line = raw.rstrip("\r\n")
    if line and not line.startswith("#"):
        key, value = line.split("=", 1)
        values[key] = value
backup = configparser.RawConfigParser(interpolation=None)
backup.read(backup_path, encoding="utf-8")
def literal(value):
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"
def identifier(value):
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,63}", value):
        raise SystemExit("unsafe SQL identifier")
    return "`" + value + "`"
app_user = values["DB_USERNAME"]
backup_user = backup["client"]["user"]
print("CREATE DATABASE {} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;".format(identifier(database)))
for user, password in ((app_user, values["DB_PASSWORD"]), (backup_user, backup["client"]["password"])):
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,31}", user):
        raise SystemExit("unsafe MySQL account name")
    print("CREATE USER {}@'127.0.0.1' IDENTIFIED BY {};".format(literal(user), literal(password)))
print("GRANT ALL PRIVILEGES ON {0}.* TO {1}@'127.0.0.1';".format(identifier(database), literal(app_user)))
print("GRANT SELECT, SHOW VIEW, TRIGGER, LOCK TABLES ON {0}.* TO {1}@'127.0.0.1';".format(identifier(database), literal(backup_user)))
print("GRANT SHOW_ROUTINE ON *.* TO {}@'127.0.0.1';".format(literal(backup_user)))
PY
}

verify_database_contract() (
  if [[ "$TEST_MODE" == true ]]; then
    [[ "$(cat "$(host_path /var/lib/mysql/joysong-bootstrap-database)")" == "$DATABASE_NAME" ]] ||
      fail "UAT database identity drifted"
    return 0
  fi
  local app_defaults app_user backup_user selected app_grants backup_grants global_privileges grantable_count backup_privileges
  readarray -t users < <("$PYTHON_BIN" -I - "$ENV_FILE" "$MYSQL_CONFIG" <<'PY'
import configparser, sys
values = {}
for raw in open(sys.argv[1], encoding="utf-8"):
    line = raw.rstrip("\r\n")
    if line and not line.startswith("#"):
        key, value = line.split("=", 1)
        values[key] = value
backup = configparser.RawConfigParser(interpolation=None)
backup.read(sys.argv[2], encoding="utf-8")
print(values["DB_USERNAME"])
print(backup["client"]["user"])
PY
  )
  app_user="${users[0]}"
  backup_user="${users[1]}"
  app_defaults="$(mktemp /run/.joysong-app-mysql.XXXXXX)"
  trap 'rm -f -- "$app_defaults"' EXIT
  "$PYTHON_BIN" -I - "$ENV_FILE" >"$app_defaults" <<'PY'
import sys
values = {}
for raw in open(sys.argv[1], encoding="utf-8"):
    line = raw.rstrip("\r\n")
    if line and not line.startswith("#"):
        key, value = line.split("=", 1)
        values[key] = value
print("[client]")
print("host=127.0.0.1")
print("port=3306")
print("protocol=TCP")
print("user={}".format(values["DB_USERNAME"]))
print("password={}".format(values["DB_PASSWORD"]))
print("database=myapp_worktree_uat")
PY
  chmod 0600 "$app_defaults"
  selected="$(mysql --defaults-extra-file="$app_defaults" --batch --skip-column-names --execute='SELECT DATABASE()')"
  [[ "$selected" == "$DATABASE_NAME" ]] || fail "application MySQL credential or database identity drifted"
  selected="$(mysql --defaults-extra-file="$MYSQL_CONFIG" --batch --skip-column-names --execute='SELECT DATABASE()')"
  [[ "$selected" == "$DATABASE_NAME" ]] || fail "backup MySQL credential or database identity drifted"

  app_grants="$(mysql --protocol=socket --user=root --batch --skip-column-names \
    --execute="SHOW GRANTS FOR '$app_user'@'127.0.0.1'")"
  [[ "$(wc -l <<<"$app_grants" | tr -d ' ')" == 2 ]] || fail "application MySQL grants drifted"
  grep -Fx "GRANT ALL PRIVILEGES ON \`$DATABASE_NAME\`.* TO \`$app_user\`@\`127.0.0.1\`" <<<"$app_grants" >/dev/null ||
    fail "application MySQL database grant drifted"
  backup_privileges="$(mysql --protocol=socket --user=root --batch --skip-column-names --execute="
    SELECT GROUP_CONCAT(PRIVILEGE_TYPE ORDER BY PRIVILEGE_TYPE SEPARATOR ',')
    FROM information_schema.SCHEMA_PRIVILEGES
    WHERE GRANTEE='''$backup_user''@''127.0.0.1''' AND TABLE_SCHEMA='$DATABASE_NAME';")"
  [[ "$backup_privileges" == 'LOCK TABLES,SELECT,SHOW VIEW,TRIGGER' ]] || fail "backup MySQL schema grants drifted"
  backup_grants="$(mysql --protocol=socket --user=root --batch --skip-column-names \
    --execute="SHOW GRANTS FOR '$backup_user'@'127.0.0.1'")"
  [[ "$(wc -l <<<"$backup_grants" | tr -d ' ')" == 3 ]] || fail "backup MySQL grant count drifted"
  grep -Fx "GRANT SHOW_ROUTINE ON *.* TO \`$backup_user\`@\`127.0.0.1\`" <<<"$backup_grants" >/dev/null ||
    fail "backup MySQL SHOW_ROUTINE grant drifted"
  global_privileges="$(mysql --protocol=socket --user=root --batch --skip-column-names --execute="
    SELECT GROUP_CONCAT(PRIVILEGE_TYPE ORDER BY PRIVILEGE_TYPE SEPARATOR ',')
    FROM information_schema.USER_PRIVILEGES
    WHERE GRANTEE IN ('''$app_user''@''127.0.0.1''','''$backup_user''@''127.0.0.1''') AND PRIVILEGE_TYPE <> 'USAGE';")"
  [[ "$global_privileges" == SHOW_ROUTINE ]] || fail "UAT MySQL global privilege set drifted"
  grantable_count="$(mysql --protocol=socket --user=root --batch --skip-column-names --execute="
    SELECT COUNT(*) FROM (
      SELECT IS_GRANTABLE FROM information_schema.USER_PRIVILEGES
      WHERE GRANTEE IN ('''$app_user''@''127.0.0.1''','''$backup_user''@''127.0.0.1''') AND PRIVILEGE_TYPE <> 'USAGE'
      UNION ALL
      SELECT IS_GRANTABLE FROM information_schema.SCHEMA_PRIVILEGES
      WHERE GRANTEE IN ('''$app_user''@''127.0.0.1''','''$backup_user''@''127.0.0.1''')
    ) granted WHERE IS_GRANTABLE='YES';")"
  [[ "$grantable_count" == 0 ]] || fail "UAT MySQL account has grant authority"
)

extract_and_register_runner() {
  local archive="$1" token_file="$2" version="$3" expected_sha="$4" actual_version registration_token
  install_directory root root 0700 "$RUNNER_ROOT_PATH"
  [[ "$(sha256sum "$archive" | awk '{print $1}')" == "$expected_sha" ]] || fail "runner archive changed before extraction"
  tar --extract --gzip --file "$archive" --directory "$RUNNER_ROOT_PATH" --no-same-owner --no-same-permissions
  if [[ "$TEST_MODE" == false ]]; then chown -R "$RUNNER_USER:$RUNNER_USER" "$RUNNER_ROOT_PATH"; fi
  chmod 0755 "$RUNNER_ROOT_PATH"
  [[ -f "$RUNNER_ROOT_PATH/bin/Runner.Listener" && ! -L "$RUNNER_ROOT_PATH/bin/Runner.Listener" ]] || fail "runner listener is missing"
  [[ -f "$RUNNER_ROOT_PATH/config.sh" && ! -L "$RUNNER_ROOT_PATH/config.sh" ]] || fail "runner config.sh is missing"
  [[ -f "$RUNNER_ROOT_PATH/runsvc.sh" && ! -L "$RUNNER_ROOT_PATH/runsvc.sh" ]] || fail "runner runsvc.sh is missing"
  registration_token="$(tr -d '\r\n' <"$token_file")"
  [[ "${#registration_token}" -ge 20 && "${#registration_token}" -le 255 && "$registration_token" != *[[:space:]]* ]] || fail "registration token file is malformed"
  if [[ "$TEST_MODE" == true ]]; then
    actual_version="$("$RUNNER_ROOT_PATH/bin/Runner.Listener" --version)"
    (cd "$RUNNER_ROOT_PATH" && ./config.sh --unattended --url "$REPOSITORY_URL" --token "$registration_token" \
      --name "$RUNNER_NAME" --labels "$RUNNER_LABEL" --no-default-labels --work "$RUNNER_HOME")
  else
    actual_version="$(runuser -u "$RUNNER_USER" -- /usr/bin/env -i HOME="$RUNNER_HOME" \
      PATH=/usr/local/bin:/usr/bin:/bin "$RUNNER_ROOT/bin/Runner.Listener" --version)"
    cd "$RUNNER_ROOT"
    runuser -u "$RUNNER_USER" -- /usr/bin/env -i HOME="$RUNNER_HOME" PATH=/usr/local/bin:/usr/bin:/bin \
      ./config.sh --unattended --url "$REPOSITORY_URL" --token "$registration_token" --name "$RUNNER_NAME" \
      --labels "$RUNNER_LABEL" --no-default-labels --work "$RUNNER_HOME"
  fi
  registration_token=''
  [[ "$actual_version" == "$version" ]] || fail "runner archive version differs from the approved version"
  rm -f -- "$token_file"
}

write_runner_unit() {
  local stage
  stage="$(mktemp "$(dirname "$RUNNER_UNIT")/.joysong-uat-runner.XXXXXX")"
  render_runner_unit >"$stage"
  chmod 0644 "$stage"
  mv -T -- "$stage" "$RUNNER_UNIT"
}

render_runner_unit() {
  cat <<'UNIT'
[Unit]
Description=GitHub Actions Runner for JoySong UAT
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/opt/joysong-actions-runner/runsvc.sh
User=joysong-gh-runner
Group=joysong-gh-runner
WorkingDirectory=/opt/joysong-actions-runner
KillMode=process
KillSignal=SIGTERM
TimeoutStopSec=5min

[Install]
WantedBy=multi-user.target
UNIT
}

render_sudoers() {
  printf 'Defaults:%s env_reset, always_set_home, secure_path=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n' "$RUNNER_USER"
  printf '%s ALL=(root) NOPASSWD:NOSETENV: %s *\n' "$RUNNER_USER" "${ENTRYPOINT#"$ROOT_PREFIX"}"
}

write_sudoers() {
  local stage
  stage="$(mktemp "$(dirname "$SUDOERS")/.joysong-uat-deploy.XXXXXX")"
  render_sudoers >"$stage"
  chmod 0440 "$stage"
  if [[ "$TEST_MODE" == false ]]; then visudo -cf "$stage" >/dev/null || fail "generated sudoers rule is invalid"; fi
  mv -T -- "$stage" "$SUDOERS"
}

write_host_contract() {
  local systemd_sha nginx_sha stage
  systemd_sha="$(sha256sum "$SYSTEMD_UNIT" | awk '{print $1}')"
  nginx_sha="$(sha256sum "$NGINX_SITE" | awk '{print $1}')"
  stage="$(mktemp "$ENV_DIR/.host-contract.XXXXXX")"
  printf 'SYSTEMD_UNIT_SHA256=%s\nNGINX_SITE_SHA256=%s\n' "$systemd_sha" "$nginx_sha" >"$stage"
  chmod 0600 "$stage"
  if [[ "$TEST_MODE" == false ]]; then chown root:root "$stage"; fi
  mv -T -- "$stage" "$HOST_CONTRACT"
}

verify_installed_layout() {
  local path expected
  for path in "$ENV_DIR" "$BACKEND_ROOT" "$BACKEND_ROOT/releases" "$ADMIN_ROOT" "$ADMIN_ROOT/releases" \
    "$DATA_ROOT" "$DATA_ROOT/uploads" "$DATA_ROOT/private" "$DATA_ROOT/upload-staging" \
    "$DEPLOY_ROOT" "$DEPLOY_ROOT/state" "$DEPLOY_ROOT/state/releases" "$DEPLOY_ROOT/backups" \
    "$DEPLOY_ROOT/incoming" "$RUNNER_HOME_PATH" "$RUNNER_ROOT_PATH"; do
    [[ -d "$path" && ! -L "$path" ]] || fail "installed directory is missing or unsafe: ${path#"$ROOT_PREFIX"}"
  done
  require_root_protected_file "$DEPLOY_ROOT/state/deploy.lock" 600 "deployment lock"
  require_root_protected_file "$RUNNER_UNIT" 644 "runner unit"
  [[ "$(cat "$RUNNER_UNIT")" == "$(render_runner_unit)" ]] || fail "runner unit drifted"
  [[ "$(cat "$SUDOERS")" == "$(render_sudoers)" ]] || fail "runner sudoers content drifted"
  if [[ "$TEST_MODE" == true ]]; then return; fi
  while IFS='|' read -r path expected; do
    [[ "$(stat -c '%U:%G:%a' "$path")" == "$expected" ]] || fail "installed directory metadata drifted: $path"
  done <<EOF
$ENV_DIR|root:root:755
$BACKEND_ROOT|root:root:755
$BACKEND_ROOT/releases|root:root:755
$ADMIN_ROOT|root:root:755
$ADMIN_ROOT/releases|root:root:755
$DATA_ROOT|root:root:711
$DATA_ROOT/uploads|$APP_USER:$NGINX_GROUP:2750
$DATA_ROOT/private|$APP_USER:$APP_GROUP:700
$DATA_ROOT/upload-staging|$APP_USER:$APP_GROUP:700
$DEPLOY_ROOT|root:root:711
$DEPLOY_ROOT/state|root:root:700
$DEPLOY_ROOT/state/releases|root:root:700
$DEPLOY_ROOT/backups|root:root:700
$DEPLOY_ROOT/incoming|$RUNNER_USER:$RUNNER_USER:700
$RUNNER_HOME_PATH|$RUNNER_USER:$RUNNER_USER:700
EOF
  [[ "$(stat -c '%U:%G:%a' "$RUNNER_ROOT_PATH")" == "$RUNNER_USER:$RUNNER_USER:755" ]] || fail "runner root metadata drifted"
  [[ "$(systemctl show "$RUNNER_SERVICE" -p User --value)" == "$RUNNER_USER" ]] || fail "runner service user drifted"
  [[ "$(runuser -u "$RUNNER_USER" -- /usr/bin/env -i HOME="$RUNNER_HOME" PATH=/usr/local/bin:/usr/bin:/bin \
    "$RUNNER_ROOT/bin/Runner.Listener" --version)" == "$EXPECTED_RUNNER_VERSION" ]] ||
    fail "runner installation version drifted"
  require_regular_file "$RUNNER_ROOT_PATH/.credentials" "runner credentials"
  nginx -t >/dev/null
}

verify_host_contract() {
  local source_dir="$1" secrets_dir="$2" archive="$3" version="$4" sha="$5" expected
  validate_platform
  resolve_sources "$source_dir"
  validate_runner_archive "$archive" "$version" "$sha"
  validate_secrets_directory "$secrets_dir" repeat
  require_input_file "$secrets_dir/joysong.env" "application secret input"
  require_input_file "$secrets_dir/joysong-uat-backup.cnf" "database backup secret input"
  validate_environment_file "$secrets_dir/joysong.env"
  validate_mysql_option_file "$secrets_dir/joysong-uat-backup.cnf" "$secrets_dir/joysong.env"
  require_root_protected_file "$HOST_CONTRACT" 600 "host contract"
  expected="$(printf 'SYSTEMD_UNIT_SHA256=%s\nNGINX_SITE_SHA256=%s' \
    "$(sha256sum "$SYSTEMD_SOURCE" | awk '{print $1}')" "$(sha256sum "$NGINX_SOURCE" | awk '{print $1}')")"
  [[ "$(cat "$HOST_CONTRACT")" == "$expected" ]] || fail "host contract drifted"
  cmp -s "$SYSTEMD_SOURCE" "$SYSTEMD_UNIT" || fail "installed systemd unit drifted"
  cmp -s "$NGINX_SOURCE" "$NGINX_SITE" || fail "installed Nginx site drifted"
  cmp -s "$DEPLOY_SOURCE" "$ENTRYPOINT" || fail "installed deployment entrypoint drifted"
  cmp -s "$VERIFY_SOURCE" "$INSTALL_ROOT/verify-uat-release.py" || fail "installed release verifier drifted"
  cmp -s "$secrets_dir/joysong.env" "$ENV_FILE" || fail "installed application secret differs from input"
  cmp -s "$secrets_dir/joysong-uat-backup.cnf" "$MYSQL_CONFIG" || fail "installed backup secret differs from input"
  require_root_protected_file "$ENV_FILE" 640 "runtime configuration"
  require_root_protected_file "$MYSQL_CONFIG" 600 "database backup configuration"
  require_root_protected_file "$SYSTEMD_UNIT" 644 "systemd unit"
  require_root_protected_file "$NGINX_SITE" 644 "Nginx site"
  require_root_protected_file "$SUDOERS" 440 "runner sudoers rule"
  verify_installed_layout
  verify_access_boundaries
  verify_database_contract
  verify_system_user "$APP_USER" /var/lib/joysong-demo
  verify_system_user "$RUNNER_USER" "$RUNNER_HOME"
  verify_nginx_identity
  require_regular_file "$RUNNER_ROOT_PATH/.runner" "runner registration"
  [[ "$(service_state mysql.service active)" == active ]] || fail "MySQL is not active"
  [[ "$(service_state mysql.service enabled)" == enabled ]] || fail "MySQL is not enabled"
  [[ "$(service_state nginx.service active)" == active ]] || fail "Nginx is not active"
  [[ "$(service_state nginx.service enabled)" == enabled ]] || fail "Nginx is not enabled"
  [[ "$(service_state "$RUNNER_SERVICE" active)" == active ]] || fail "runner is not active"
  [[ "$(service_state "$RUNNER_SERVICE" enabled)" == enabled ]] || fail "runner is not enabled"
  [[ "$(service_state "$APP_SERVICE" active)" != active && "$(service_state "$APP_SERVICE" enabled)" != enabled ]] ||
    fail "application must remain inactive and disabled before the first release"
  printf 'JoySong UAT host contract is unchanged; no changes applied\n'
}

verify_access_boundaries() {
  if [[ "$TEST_MODE" == true ]]; then
    return
  fi
  [[ "$(stat -c '%U:%G:%a' "$DATA_ROOT/uploads")" == "$APP_USER:$NGINX_GROUP:2750" ]] || fail "uploads ownership/mode drifted"
  [[ "$(stat -c '%U:%G:%a' "$DATA_ROOT/private")" == "$APP_USER:$APP_GROUP:700" ]] || fail "private ownership/mode drifted"
  [[ "$(stat -c '%U:%G:%a' "$DATA_ROOT/upload-staging")" == "$APP_USER:$APP_GROUP:700" ]] || fail "staging ownership/mode drifted"
  runuser -u "$APP_USER" -- test -w "$DATA_ROOT/uploads" || fail "application cannot write uploads"
  runuser -u "$NGINX_USER" -- test -r "$DATA_ROOT/uploads" || fail "Nginx cannot read uploads"
  runuser -u "$RUNNER_USER" -- test ! -r "$ENV_FILE" || fail "runner can read application secrets"
  runuser -u "$RUNNER_USER" -- test ! -r "$MYSQL_CONFIG" || fail "runner can read database secrets"
  runuser -u "$RUNNER_USER" -- test ! -r "$DATA_ROOT/private" || fail "runner can read private data"
  runuser -u "$RUNNER_USER" -- test ! -r "$DATA_ROOT/upload-staging" || fail "runner can read staging data"
  runuser -u "$RUNNER_USER" -- test ! -r "$DEPLOY_ROOT/state" || fail "runner can read deploy state"
  runuser -u "$RUNNER_USER" -- test ! -r "$DEPLOY_ROOT/backups" || fail "runner can read deploy backups"
  runuser -u "$RUNNER_USER" -- test -w "$DEPLOY_ROOT/incoming" || fail "runner cannot write incoming"
}

apply_fresh() {
  local source_dir="$1" secrets_dir="$2" archive="$3" version="$4" sha="$5" token_file
  preflight
  resolve_sources "$source_dir"
  validate_secrets_directory "$secrets_dir" fresh
  require_input_file "$secrets_dir/joysong.env" "application secret input"
  require_input_file "$secrets_dir/joysong-uat-backup.cnf" "database backup secret input"
  token_file="$secrets_dir/runner-registration-token"
  require_input_file "$token_file" "runner registration token"
  trap '[[ -z "${token_file:-}" ]] || rm -f -- "$token_file"' EXIT INT TERM
  validate_environment_file "$secrets_dir/joysong.env"
  validate_mysql_option_file "$secrets_dir/joysong-uat-backup.cnf" "$secrets_dir/joysong.env"
  validate_runner_archive "$archive" "$version" "$sha"
  install_packages
  verify_nginx_identity
  ensure_system_user "$APP_USER" /var/lib/joysong-demo
  ensure_system_user "$RUNNER_USER" "$RUNNER_HOME"

  install_directory root root 0755 "$ENV_DIR" "$(dirname "$MYSQL_CONFIG")" "$(dirname "$SYSTEMD_UNIT")" \
    "$(dirname "$NGINX_SITE")" "$INSTALL_ROOT" "$(dirname "$ENTRYPOINT")" "$(dirname "$SUDOERS")"
  install_directory root root 0755 "$BACKEND_ROOT" "$BACKEND_ROOT/releases" "$ADMIN_ROOT" "$ADMIN_ROOT/releases"
  install_directory root root 0711 "$DATA_ROOT" "$DEPLOY_ROOT"
  install_directory "$APP_USER" "$NGINX_GROUP" 2750 "$DATA_ROOT/uploads"
  install_directory "$APP_USER" "$APP_GROUP" 0700 "$DATA_ROOT/private" "$DATA_ROOT/upload-staging"
  install_directory root root 0700 "$DEPLOY_ROOT/state" "$DEPLOY_ROOT/state/releases" "$DEPLOY_ROOT/backups"
  install_directory "$RUNNER_USER" "$RUNNER_USER" 0700 "$DEPLOY_ROOT/incoming" "$RUNNER_HOME_PATH"
  install_file root "$APP_GROUP" 0640 "$secrets_dir/joysong.env" "$ENV_FILE"
  install_file root root 0600 "$secrets_dir/joysong-uat-backup.cnf" "$MYSQL_CONFIG"
  install_file root root 0755 "$DEPLOY_SOURCE" "$ENTRYPOINT"
  install_file root root 0755 "$VERIFY_SOURCE" "$INSTALL_ROOT/verify-uat-release.py"
  install_file root root 0644 "$SYSTEMD_SOURCE" "$SYSTEMD_UNIT"
  prepare_nginx_default
  install_file root root 0644 "$NGINX_SOURCE" "$NGINX_SITE"
  install_file root root 0600 /dev/null "$DEPLOY_ROOT/state/deploy.lock"

  provision_database "$secrets_dir/joysong.env" "$secrets_dir/joysong-uat-backup.cnf"
  extract_and_register_runner "$archive" "$token_file" "$version" "$sha"
  trap - EXIT INT TERM
  write_runner_unit
  write_sudoers
  verify_access_boundaries
  if [[ "$TEST_MODE" == false ]]; then
    systemd-analyze verify "$SYSTEMD_UNIT" "$RUNNER_UNIT" >/dev/null 2>&1 || fail "systemd unit validation failed"
    nginx -t
    systemctl daemon-reload
    systemctl enable --now nginx
    systemctl reload nginx
    systemctl enable "$RUNNER_SERVICE"
    systemctl start "$RUNNER_SERVICE"
  else
    set_test_service_state mysql.service active active
    set_test_service_state mysql.service enabled enabled
    set_test_service_state nginx.service active active
    set_test_service_state nginx.service enabled enabled
    set_test_service_state "$RUNNER_SERVICE" enabled enabled
    set_test_service_state "$RUNNER_SERVICE" active active
  fi
  write_host_contract
  verify_host_contract "$source_dir" "$secrets_dir" "$archive" "$version" "$sha"
}

main() {
  (($# >= 1)) || fail "usage: bootstrap-uat-host.sh preflight | apply SOURCE_DIR SECRETS_DIR RUNNER_ARCHIVE RUNNER_VERSION RUNNER_SHA256"
  case "$1" in
    preflight)
      (($# == 1)) || fail "preflight takes no arguments"
      preflight
      ;;
    apply)
      (($# == 6)) || fail "apply requires source dir, secrets dir, runner archive, version and SHA-256"
      local source_dir="$2" secrets_dir="$3" archive="$4"
      [[ -d "$source_dir" && ! -L "$source_dir" ]] || fail "source directory is unsafe"
      [[ -d "$secrets_dir" && ! -L "$secrets_dir" ]] || fail "secrets directory is unsafe"
      [[ -f "$archive" && ! -L "$archive" ]] || fail "runner archive is unsafe"
      source_dir="$(readlink -f "$source_dir")"
      secrets_dir="$(readlink -f "$secrets_dir")"
      archive="$(readlink -f "$archive")"
      require_root_path_chain "$source_dir" "bootstrap source"
      require_root_path_chain "$secrets_dir" "secrets directory"
      require_root_path_chain "$archive" "runner archive"
      if [[ -e "$HOST_CONTRACT" || -L "$HOST_CONTRACT" ]]; then
        verify_host_contract "$source_dir" "$secrets_dir" "$archive" "$5" "$6"
      else
        apply_fresh "$source_dir" "$secrets_dir" "$archive" "$5" "$6"
      fi
      ;;
    *) fail "unsupported action: $1" ;;
  esac
}

main "$@"
