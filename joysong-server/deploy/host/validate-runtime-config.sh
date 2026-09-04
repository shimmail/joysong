#!/usr/bin/env bash
set -Eeuo pipefail

environment="${1:-}"
environment_file="${2:-/etc/joysong/${environment}/joysong.env}"
expected_database_host="${3:-}"
expected_server_base_url="${4:-}"
expected_admin_origin="${5:-}"
(($# <= 5)) || { printf 'usage: %s <uat|prod> [environment-file] [expected-database-host] [expected-server-base-url] [expected-admin-origin]\n' "$0" >&2; exit 2; }

fail() {
  printf 'runtime configuration rejected: %s\n' "$*" >&2
  exit 2
}

[[ "$environment" == "uat" || "$environment" == "prod" ]] || fail "environment must be uat or prod"
expected_environment_file="/etc/joysong/${environment}/joysong.env"
[[ "$environment_file" == "$expected_environment_file" && ! -L "$environment_file" ]] ||
  fail "environment file must be the canonical non-symlink path: $expected_environment_file"
[[ -r "$environment_file" ]] || fail "missing readable environment file: $environment_file"
[[ "$(stat -c '%U' "$environment_file")" == "root" ]] || fail "environment file must be owned by root"
[[ "$(stat -c '%G' "$environment_file")" == "joysong-${environment}" ]] ||
  fail "environment file group must be joysong-${environment}"
[[ "$(stat -c '%a' "$environment_file")" == "640" ]] || fail "environment file mode must be 0640"

# EnvironmentFile values reach both the JVM launcher and Spring's relaxed
# environment binder. Reject alternate injection surfaces before inspecting the
# explicit application settings below; otherwise a second SPRING_* or JVM option
# could override an apparently safe profile or load attacker-controlled code.
while IFS= read -r raw_line || [[ -n "$raw_line" ]]; do
  line="${raw_line%$'\r'}"
  [[ "$line" =~ ^[[:space:]]*$ || "$line" =~ ^[[:space:]]*[#\;] ]] && continue
  [[ "$line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)= ]] ||
    fail "environment file contains an unsupported assignment"
  key="${BASH_REMATCH[1]}"
  if [[ "$key" == SPRING_* && "$key" != "SPRING_PROFILES_ACTIVE" ]]; then
    fail "$key is forbidden; only SPRING_PROFILES_ACTIVE may use the SPRING_ namespace"
  fi
  case "$key" in
    JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|JAVA_OPTS|LD_*)
      fail "$key is forbidden because it can alter JVM startup or native code loading"
      ;;
  esac
done <"$environment_file"

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

require_value() {
  local key="$1" value
  value="$(read_value "$key" || true)"
  [[ -n "$value" ]] || fail "$key must be explicitly configured"
  printf '%s' "$value"
}

require_boolean() {
  local key="$1" expected="$2" actual
  actual="$(require_value "$key")"
  [[ "$actual" == "$expected" ]] || fail "$key must be $expected for $environment"
}

profile="$(require_value SPRING_PROFILES_ACTIVE)"
address="$(require_value SERVER_ADDRESS)"
port="$(require_value SERVER_PORT)"
database_url="$(require_value DB_URL)"
server_base_url="$(require_value SERVER_BASE_URL)"
share_base_url="$(require_value APP_SHARE_BASE_URL)"
cors_allowed_origins="$(require_value CORS_ALLOWED_ORIGINS)"
[[ "$address" == "127.0.0.1" ]] || fail "SERVER_ADDRESS must be 127.0.0.1"

python3 - "$server_base_url" "$share_base_url" "$cors_allowed_origins" <<'PY' ||
  fail "SERVER_BASE_URL, APP_SHARE_BASE_URL or CORS_ALLOWED_ORIGINS is unsafe"
import sys
from urllib.parse import urlsplit

server_url, share_url, cors_value = sys.argv[1:]

def parse_https(value: str, *, origin_only: bool = False):
    if "*" in value or any(character.isspace() for character in value):
        raise ValueError("wildcards and whitespace are forbidden")
    parsed = urlsplit(value)
    if parsed.scheme != "https" or not parsed.hostname:
        raise ValueError("HTTPS with a hostname is required")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("credentials, query and fragment are forbidden")
    if origin_only and parsed.path not in ("", "/"):
        raise ValueError("an origin must not contain a path")
    _ = parsed.port
    return parsed

server = parse_https(server_url, origin_only=True)
share = parse_https(share_url)
if share.path != "/s/diary/":
    raise ValueError("share base path must be /s/diary/")
if (server.hostname, server.port) != (share.hostname, share.port):
    raise ValueError("share and server base URLs must use the same origin")
origins = [item.strip() for item in cors_value.split(",")]
if not origins or any(not item for item in origins):
    raise ValueError("CORS origin list is empty")
for origin in origins:
    parse_https(origin, origin_only=True)
PY

if [[ -n "$expected_server_base_url" || -n "$expected_admin_origin" ]]; then
  [[ -n "$expected_server_base_url" && -n "$expected_admin_origin" ]] ||
    fail "both expected public origins must be supplied together"
  [[ "$server_base_url" == "$expected_server_base_url" ]] ||
    fail "SERVER_BASE_URL does not match the required environment origin"
  [[ "$share_base_url" == "${expected_server_base_url}/s/diary/" ]] ||
    fail "APP_SHARE_BASE_URL does not match the required environment origin"
  [[ "$cors_allowed_origins" == "$expected_admin_origin" ]] ||
    fail "CORS_ALLOWED_ORIGINS must contain only the required Admin origin"
fi

service_user="joysong-${environment}"
data_root="/var/lib/joysong/${environment}"
[[ -d "$data_root" && ! -L "$data_root" ]] || fail "environment data root must be a real directory"
[[ "$(stat -c '%U' "$data_root")" == "root" ]] || fail "environment data root must be owned by root"
[[ "$(stat -c '%G' "$data_root")" == "joysong-${environment}-release" ]] ||
  fail "environment data root must use the protected release group"
[[ "$(stat -c '%a' "$data_root")" == "710" ]] || fail "environment data root mode must be 0710"
validate_data_directory() {
  local key="$1" suffix="$2" mode="$3" value expected
  value="$(require_value "$key")"
  expected="/var/lib/joysong/${environment}/${suffix}"
  [[ "$value" == "$expected" ]] || fail "$key must be $expected"
  [[ -d "$value" && ! -L "$value" ]] || fail "$key must identify a real directory"
  [[ "$(stat -c '%U' "$value")" == "$service_user" ]] || fail "$key must be owned by $service_user"
  [[ "$(stat -c '%a' "$value")" == "$mode" ]] || fail "$key directory mode must be 0$mode"
}

validate_data_directory UPLOAD_LOCAL_DIR uploads 2750
validate_data_directory UPLOAD_STAGING_DIR upload-staging 700
validate_data_directory UPLOAD_PRIVATE_DIR private 700
require_value DB_USERNAME >/dev/null
require_value DB_PASSWORD >/dev/null

[[ "$database_url" == jdbc:mysql://* ]] || fail "DB_URL must be a MySQL JDBC URL"
[[ "${database_url,,}" != *createdatabaseifnotexist* ]] ||
  fail "DB_URL must not contain createDatabaseIfNotExist"
database_authority="${database_url#jdbc:mysql://}"
database_authority="${database_authority%%/*}"
[[ "$database_authority" != *'@'* && -n "$database_authority" ]] ||
  fail "DB_URL authority must not contain credentials"
[[ "$database_authority" =~ ^([A-Za-z0-9][A-Za-z0-9.-]*)(:([0-9]{1,5}))?$ ]] ||
  fail "DB_URL must use one explicit DNS hostname and optional numeric port"
database_host="${BASH_REMATCH[1],,}"
database_port="${BASH_REMATCH[3]:-3306}"
((10#$database_port >= 1 && 10#$database_port <= 65535)) || fail "DB_URL port is invalid"
if [[ -n "$expected_database_host" ]]; then
  [[ "$expected_database_host" =~ ^[a-z0-9][a-z0-9.-]*$ ]] || fail "expected database host is invalid"
  [[ "$database_host" == "$expected_database_host" ]] ||
    fail "DB_URL host does not match the expected RDS instance endpoint"
fi
database_name="${database_url%%\?*}"
database_name="${database_name##*/}"
[[ "$database_name" =~ ^[A-Za-z][A-Za-z0-9_]{0,63}$ ]] || fail "DB_URL must select an explicit safe database name"
printf 'Resolved database host: %s\n' "$database_host"
printf 'Resolved database port: %s\n' "$database_port"
printf 'Resolved database name: %s\n' "$database_name"

case "$environment" in
  uat)
    [[ "$profile" == "demo" ]] || fail "UAT must use the exact demo profile"
    [[ "$port" == "8081" ]] || fail "UAT must listen on port 8081"
    [[ "$database_name" =~ ^myapp_worktree_[A-Za-z0-9_]+$ ]] ||
      fail "UAT database must start with myapp_worktree_"
    demo_database_name="$(require_value DEMO_DATABASE_NAME)"
    [[ "$demo_database_name" == "$database_name" ]] ||
      fail "DEMO_DATABASE_NAME must equal the database selected by DB_URL"
    require_boolean DEMO_DATA_ENABLED false
    demo_data_action="$(read_value DEMO_DATA_ACTION || true)"
    [[ -z "$demo_data_action" || "$demo_data_action" == "VERIFY" ]] ||
      fail "normal UAT service must not run Demo APPLY"
    [[ -z "$(read_value DEMO_ACCOUNT_PASSWORD || true)" ]] ||
      fail "temporary DEMO_ACCOUNT_PASSWORD must be removed after seed verification"
    require_boolean SMS_ENABLED false
    [[ -z "$(read_value GOOGLE_CLIENT_ID || true)" ]] || fail "GOOGLE_CLIENT_ID is forbidden in UAT"
    [[ -z "$(read_value GOOGLE_PROXY_URL || true)" ]] || fail "GOOGLE_PROXY_URL is forbidden in UAT"
    require_boolean OSS_ENABLED true
    [[ "$(require_value OSS_CREDENTIAL_MODE)" == "ecs-ram-role" ]] ||
      fail "UAT OSS must use the ECS RAM role credential mode"
    require_value OSS_ECS_RAM_ROLE_NAME >/dev/null
    [[ -z "$(read_value OSS_ACCESS_KEY_ID || true)" ]] || fail "static OSS AccessKey is forbidden in UAT"
    [[ -z "$(read_value OSS_ACCESS_KEY_SECRET || true)" ]] || fail "static OSS secret is forbidden in UAT"
    [[ "$(require_value PRIVATE_FILE_STORAGE_MODE)" == "local" ]] ||
      fail "UAT private files must remain in the isolated local private directory"
    require_boolean ALIPAY_PLUS_SIMULATED_ENABLED true
    require_boolean ALIPAY_PLUS_AUTO_PAY_ON_ORDER_CREATE_ENABLED false
    require_boolean PAYMENT_RECONCILIATION_ENABLED false
    require_boolean STRIPE_LEGACY_ENABLED false
    ;;
  prod)
    [[ "$profile" == "prod" ]] || fail "production must use the exact prod profile"
    [[ "$port" == "8080" ]] || fail "production must listen on port 8080"
    [[ "$database_name" != myapp_worktree_* ]] || fail "production must not use a UAT worktree database"
    case "$database_name" in
      mysql|information_schema|performance_schema|sys) fail "production must not use a system database" ;;
    esac
    if grep -Eq '^[[:space:]]*DEMO_DATABASE_NAME=' "$environment_file"; then
      fail "DEMO_DATABASE_NAME is forbidden in production"
    fi
    require_boolean ALIPAY_PLUS_SIMULATED_ENABLED false
    require_boolean ALIPAY_PLUS_AUTO_PAY_ON_ORDER_CREATE_ENABLED false
    require_boolean PAYMENT_RECONCILIATION_ENABLED false
    require_boolean STRIPE_LEGACY_ENABLED false
    ;;
esac

printf 'Runtime configuration valid for %s (profile=%s, port=%s, database=%s).\n' \
  "$environment" "$profile" "$port" "$database_name"
