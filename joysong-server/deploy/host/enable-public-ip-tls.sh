#!/bin/bash
set -Eeuo pipefail
umask 077

readonly EXPECTED_IP=121.41.230.98
readonly WEBROOT=/var/lib/letsencrypt
readonly SITE=/etc/nginx/conf.d/joysong-public-ip.conf
readonly CHALLENGE_SITE=/etc/nginx/conf.d/joysong-public-ip-challenge.conf
readonly DEPLOY_HOOK=/etc/letsencrypt/renewal-hooks/deploy/joysong-nginx-reload
readonly ENV_FILE=/etc/joysong-demo/joysong.env
readonly SERVICE=joysong-demo.service
readonly LOCK_FILE=/run/lock/joysong-public-ip-tls.lock
readonly DRY_RUN_LOG=/var/log/letsencrypt/joysong-renew-dry-run.log

TXN_DIR=''
RENEWAL_TIMER=''
COMMITTED=false
ROLLBACK_RUNNING=false
SERVICE_ACTIVE=''
SERVICE_ENABLED=''
TIMER_ACTIVE=''
TIMER_ENABLED=''
CERT_NAME=''
STAGING_CERT_NAME=''
CHALLENGE_ACTIVATED=false
REUSE_CERT=false
declare -A INITIAL_PRESENT=()
declare -A BACKUP_PATH=()
declare -a STAGING_PATHS=()

fail() { printf 'JoySong public IP TLS setup failed: %s\n' "$*" >&2; exit 2; }

version_at_least_5_4() {
  certbot --version 2>&1 | sed -n 's/^certbot \([0-9][0-9.]*\)$/\1/p' |
    awk -F. '{ exit !($1 > 5 || ($1 == 5 && $2 >= 4)) }'
}

require_regular_single_link() {
  local path="$1" label="$2"
  [[ -f "$path" && ! -L "$path" && "$(stat -c %h "$path")" == 1 ]] ||
    fail "$label must be one regular non-link file"
}

require_loopback_listener() {
  local port="$1" lines
  lines="$(ss -H -ltn "sport = :$port")"
  [[ -n "$lines" ]] || fail "TCP $port is not listening"
  if printf '%s\n' "$lines" | awk '{print $4}' |
     grep -Ev '^(127\.0\.0\.1|\[::ffff:127\.0\.0\.1\]):' >/dev/null; then
    fail "TCP $port must remain bound only to 127.0.0.1"
  fi
}

unit_state() { systemctl "$1" "$2" 2>/dev/null || true; }

detect_renewal_timer() {
  if systemctl list-unit-files certbot.timer --no-legend 2>/dev/null | grep -q '^certbot.timer'; then
    RENEWAL_TIMER=certbot.timer
  elif systemctl list-unit-files snap.certbot.renew.timer --no-legend 2>/dev/null |
       grep -q '^snap.certbot.renew.timer'; then
    RENEWAL_TIMER=snap.certbot.renew.timer
  else
    fail "Certbot renewal timer is unavailable"
  fi
}

require_restorable_unit_state() {
  local unit="$1" active="$2" enabled="$3"
  [[ "$active" == active || "$active" == inactive ]] ||
    fail "$unit has unsupported active state: $active"
  case "$enabled" in
    enabled|enabled-runtime|disabled|static|indirect|generated|transient) ;;
    *) fail "$unit has unsupported enablement state: $enabled" ;;
  esac
}

preflight() {
  local template="$1" required_command key
  for required_command in certbot nginx openssl python3 curl flock sed install stat systemctl ss \
    grep awk head cp mv rm mktemp dirname basename; do
    command -v "$required_command" >/dev/null ||
      fail "required command is unavailable: $required_command"
  done
  require_regular_single_link "$template" "Nginx template"
  require_regular_single_link "$ENV_FILE" "runtime environment file"
  for key in SERVER_BASE_URL APP_SHARE_BASE_URL CORS_ALLOWED_ORIGINS; do
    [[ "$(grep -Ec "^${key}=" "$ENV_FILE" || true)" == 1 ]] ||
      fail "runtime environment file must contain exactly one $key"
  done
  [[ -d "$WEBROOT" && ! -L "$WEBROOT" ]] || fail "ACME webroot must already be a real directory"
  [[ -d "$(dirname "$DEPLOY_HOOK")" && ! -L "$(dirname "$DEPLOY_HOOK")" ]] ||
    fail "Certbot deploy-hook directory is unavailable or unsafe"
  [[ -d /var/backups && ! -L /var/backups ]] || fail "backup root is unavailable or unsafe"
  systemctl is-active --quiet nginx || fail "Nginx must be active before setup"
  nginx -t >/dev/null
  systemctl cat "$SERVICE" >/dev/null 2>&1 || fail "$SERVICE is unavailable"
  version_at_least_5_4 || fail "Certbot 5.4 or newer is required before setup"
  detect_renewal_timer
  SERVICE_ACTIVE="$(unit_state is-active "$SERVICE")"
  SERVICE_ENABLED="$(unit_state is-enabled "$SERVICE")"
  TIMER_ACTIVE="$(unit_state is-active "$RENEWAL_TIMER")"
  TIMER_ENABLED="$(unit_state is-enabled "$RENEWAL_TIMER")"
  require_restorable_unit_state "$SERVICE" "$SERVICE_ACTIVE" "$SERVICE_ENABLED"
  require_restorable_unit_state "$RENEWAL_TIMER" "$TIMER_ACTIVE" "$TIMER_ENABLED"
  [[ "$TIMER_ENABLED" == enabled || "$TIMER_ENABLED" == enabled-runtime ||
     "$TIMER_ENABLED" == disabled ]] ||
    fail "$RENEWAL_TIMER cannot satisfy the required enabled renewal contract"
  [[ "$SERVICE_ACTIVE" == active ]] || fail "$SERVICE must be active before setup"
  require_loopback_listener 8080
  require_loopback_listener 3306
  curl --fail --silent --show-error --max-time 5 \
    http://127.0.0.1:8080/actuator/health >/dev/null || fail "$SERVICE is unhealthy before setup"
  [[ -f /var/www/joysong-demo/current/index.html ]] ||
    fail "the atomically managed Admin current release is unavailable"
}

track_file() {
  local key="$1" path="$2"
  if [[ -e "$path" || -L "$path" ]]; then
    require_regular_single_link "$path" "$key"
    INITIAL_PRESENT["$key"]=true
    BACKUP_PATH["$key"]="$TXN_DIR/$key"
    cp -a -- "$path" "${BACKUP_PATH[$key]}"
  else
    INITIAL_PRESENT["$key"]=false
    BACKUP_PATH["$key"]=''
  fi
}

restore_file() {
  local key="$1" path="$2" staged
  if [[ "${INITIAL_PRESENT[$key]}" == true ]]; then
    staged="${path}.rollback.$$"
    STAGING_PATHS+=("$staged")
    cp -a -- "${BACKUP_PATH[$key]}" "$staged" && mv -Tf -- "$staged" "$path"
  else
    rm -f -- "$path"
  fi
}

restore_unit_state() {
  local unit="$1" active="$2" enabled="$3" restart_active="$4"
  case "$enabled" in
    enabled) systemctl enable "$unit" >/dev/null 2>&1 || return 1 ;;
    enabled-runtime)
      systemctl disable "$unit" >/dev/null 2>&1 || return 1
      systemctl enable --runtime "$unit" >/dev/null 2>&1 || return 1
      ;;
    disabled) systemctl disable "$unit" >/dev/null 2>&1 || return 1 ;;
    static|indirect|generated|transient) ;;
    *) return 1 ;;
  esac
  if [[ "$active" == active && "$restart_active" == true ]]; then systemctl restart "$unit" >/dev/null 2>&1
  elif [[ "$active" == active ]]; then systemctl start "$unit" >/dev/null 2>&1
  else systemctl stop "$unit" >/dev/null 2>&1
  fi
}

rollback() {
  local original_status="$1" failed=false
  [[ "$COMMITTED" == false && "$ROLLBACK_RUNNING" == false && -n "$TXN_DIR" ]] ||
    return "$original_status"
  ROLLBACK_RUNNING=true
  trap - ERR INT TERM EXIT
  set +e
  restore_file env "$ENV_FILE" || failed=true
  restore_file hook "$DEPLOY_HOOK" || failed=true
  restore_file site "$SITE" || failed=true
  restore_file challenge "$CHALLENGE_SITE" || failed=true
  restore_file dry_log "$DRY_RUN_LOG" || failed=true
  ((${#STAGING_PATHS[@]} == 0)) || rm -f -- "${STAGING_PATHS[@]}" || failed=true
  if nginx -t >/dev/null 2>&1; then systemctl reload nginx || failed=true
  else failed=true
  fi
  restore_unit_state "$RENEWAL_TIMER" "$TIMER_ACTIVE" "$TIMER_ENABLED" false || failed=true
  restore_unit_state "$SERVICE" "$SERVICE_ACTIVE" "$SERVICE_ENABLED" true || failed=true
  if [[ -n "$STAGING_CERT_NAME" && -d "/etc/letsencrypt/live/$STAGING_CERT_NAME" ]]; then
    certbot delete --cert-name "$STAGING_CERT_NAME" --non-interactive >/dev/null 2>&1 || failed=true
  fi
  if [[ "$REUSE_CERT" == false && -n "$CERT_NAME" && -d "/etc/letsencrypt/live/$CERT_NAME" ]]; then
    certbot delete --cert-name "$CERT_NAME" --non-interactive >/dev/null 2>&1 || failed=true
  fi
  nginx -t >/dev/null 2>&1 || failed=true
  [[ "$SERVICE_ACTIVE" != active ]] || curl --fail --silent --max-time 5 \
    http://127.0.0.1:8080/actuator/health >/dev/null || failed=true
  if [[ "$failed" == true ]]; then
    printf 'CRITICAL: public IP TLS rollback was incomplete; inspect %s\n' "$TXN_DIR" >&2
    exit 3
  fi
  printf 'Public IP TLS setup rolled back; transaction evidence: %s\n' "$TXN_DIR" >&2
  exit "$original_status"
}

atomic_install() {
  local source="$1" target="$2" mode="$3" staged="${target}.new.$$"
  STAGING_PATHS+=("$staged")
  install -o root -g root -m "$mode" "$source" "$staged"
  mv -Tf -- "$staged" "$target"
}

render_public_origins() {
  local target="$1"
  python3 -I - "$ENV_FILE" "$target" "$EXPECTED_IP" <<'PY'
import os, stat, sys
source, target, public_ip = sys.argv[1:]
details = os.stat(source, follow_symlinks=False)
if not stat.S_ISREG(details.st_mode) or details.st_nlink != 1:
    raise SystemExit("runtime environment file must be one regular file")
updates = {
    "SERVER_BASE_URL": f"https://{public_ip}",
    "APP_SHARE_BASE_URL": f"https://{public_ip}/s/diary/",
}
public_origin = f"https://{public_ip}"
seen, lines = set(), []
with open(source, encoding="utf-8") as stream:
    for raw in stream:
        key = raw.split("=", 1)[0] if "=" in raw else ""
        if key == "CORS_ALLOWED_ORIGINS":
            origins = [value.strip() for value in raw.rstrip("\r\n").split("=", 1)[1].split(",") if value.strip()]
            if public_origin not in origins:
                origins.append(public_origin)
            lines.append(f"{key}={','.join(origins)}\n")
            seen.add(key)
        elif key in updates:
            lines.append(f"{key}={updates[key]}\n")
            seen.add(key)
        else:
            lines.append(raw)
required = set(updates) | {"CORS_ALLOWED_ORIGINS"}
if seen != required:
    raise SystemExit("runtime environment file is missing required public origin keys")
with open(target, "x", encoding="utf-8", newline="") as stream:
    stream.writelines(lines)
    stream.flush()
    os.fsync(stream.fileno())
PY
}

validate_nginx_candidate() {
  local candidate="$1" wrapper="$TXN_DIR/nginx-candidate.conf" file
  {
    printf 'pid %s/nginx.pid;\nerror_log stderr;\nevents {}\nhttp {\n' "$TXN_DIR"
    printf 'include /etc/nginx/mime.types;\n'
    for file in /etc/nginx/conf.d/*.conf; do
      [[ "$file" == "$SITE" || "$file" == "$CHALLENGE_SITE" ]] && continue
      printf 'include %s;\n' "$file"
    done
    printf 'include %s;\n}\n' "$candidate"
  } >"$wrapper"
  nginx -t -q -c "$wrapper" -p /
}

render_candidates() {
  local template="$1"
  sed "s/__PUBLIC_IP__/$EXPECTED_IP/g" "$template" >"$TXN_DIR/site.conf"
  sed -i "s#/etc/letsencrypt/live/$EXPECTED_IP/#/etc/letsencrypt/live/$CERT_NAME/#g" \
    "$TXN_DIR/site.conf"
  ! grep -q '__PUBLIC_IP__' "$TXN_DIR/site.conf" || fail "public IP template was not fully rendered"
  cat >"$TXN_DIR/challenge.conf" <<EOF
server {
    listen 80;
    server_name $EXPECTED_IP;
    location ^~ /.well-known/acme-challenge/ {
        root $WEBROOT;
        default_type text/plain;
        try_files \$uri =404;
    }
    location / { return 404; }
}
EOF
  cat >"$TXN_DIR/hook" <<'EOF'
#!/bin/sh
set -eu
nginx -t
systemctl reload nginx
EOF
  render_public_origins "$TXN_DIR/joysong.env"
  validate_nginx_candidate "$TXN_DIR/challenge.conf"
}

select_certificate_name() {
  local existing=''
  if [[ -f "$SITE" && ! -L "$SITE" ]]; then
    existing="$(sed -n 's#^[[:space:]]*ssl_certificate[[:space:]]\+/etc/letsencrypt/live/\([^/;]*\)/fullchain\.pem;[[:space:]]*$#\1#p' "$SITE" | head -n 1)"
  fi
  if [[ "$existing" =~ ^[A-Za-z0-9._-]+$ ]] &&
     [[ -f "/etc/letsencrypt/live/$existing/fullchain.pem" ]] &&
     openssl x509 -checkend 86400 -noout -in "/etc/letsencrypt/live/$existing/fullchain.pem" >/dev/null &&
     openssl x509 -checkip "$EXPECTED_IP" -noout -in "/etc/letsencrypt/live/$existing/fullchain.pem" >/dev/null; then
    CERT_NAME="$existing"
    REUSE_CERT=true
  else
    CERT_NAME="$EXPECTED_IP-$(basename "$TXN_DIR")"
  fi
  STAGING_CERT_NAME="$EXPECTED_IP-$(basename "$TXN_DIR")-staging"
}

issue_certificates() {
  [[ "$REUSE_CERT" == false ]] || return 0
  certbot certonly --staging --preferred-profile shortlived --webroot --webroot-path "$WEBROOT" \
    --ip-address "$EXPECTED_IP" --cert-name "$STAGING_CERT_NAME" --non-interactive \
    --agree-tos --register-unsafely-without-email
  certbot certonly --preferred-profile shortlived --webroot --webroot-path "$WEBROOT" \
    --ip-address "$EXPECTED_IP" --cert-name "$CERT_NAME" --non-interactive \
    --agree-tos --register-unsafely-without-email
  certbot delete --cert-name "$STAGING_CERT_NAME" --non-interactive
}

verify_certificate() {
  openssl x509 -checkend 86400 -noout -in "/etc/letsencrypt/live/$CERT_NAME/fullchain.pem"
  openssl x509 -checkip "$EXPECTED_IP" -noout -in "/etc/letsencrypt/live/$CERT_NAME/fullchain.pem"
}

wait_for_health() {
  curl --retry 30 --retry-connrefused --retry-delay 2 --max-time 5 \
    --fail --silent --show-error http://127.0.0.1:8080/actuator/health >/dev/null
}

verify_https_routes() {
  curl --noproxy '*' --retry 10 --retry-all-errors --retry-delay 1 --max-time 10 \
    --fail --silent --show-error --resolve "$EXPECTED_IP:443:127.0.0.1" \
    "https://$EXPECTED_IP/actuator/health" >/dev/null
  curl --noproxy '*' --fail --silent --show-error --max-time 10 \
    --resolve "$EXPECTED_IP:443:127.0.0.1" "https://$EXPECTED_IP/admin/" >/dev/null
  [[ "$(curl --noproxy '*' --silent --output /dev/null --max-time 10 --write-out '%{redirect_url}' \
    --resolve "$EXPECTED_IP:80:127.0.0.1" "http://$EXPECTED_IP/")" == "https://$EXPECTED_IP/" ]]
  require_loopback_listener 8080
  require_loopback_listener 3306
}

main() {
  local template="${1:-}" env_staged
  [[ "$EUID" -eq 0 ]] || fail "run as root"
  (($# == 1)) || fail "usage: enable-public-ip-tls.sh NGINX_TEMPLATE"
  mkdir -p /run/lock
  exec 9>"$LOCK_FILE"
  flock -n 9 || fail "another public IP TLS setup is already running"

  preflight "$template"

  TXN_DIR="$(mktemp -d /var/backups/joysong-public-ip-tls.XXXXXXXX)"
  chmod 0700 "$TXN_DIR"
  select_certificate_name
  track_file site "$SITE"
  track_file challenge "$CHALLENGE_SITE"
  track_file hook "$DEPLOY_HOOK"
  track_file env "$ENV_FILE"
  track_file dry_log "$DRY_RUN_LOG"
  printf 'SERVICE_ACTIVE=%s\nSERVICE_ENABLED=%s\nTIMER=%s\nTIMER_ACTIVE=%s\nTIMER_ENABLED=%s\n' \
    "$SERVICE_ACTIVE" "$SERVICE_ENABLED" "$RENEWAL_TIMER" "$TIMER_ACTIVE" "$TIMER_ENABLED" \
    >"$TXN_DIR/unit-state"
  chmod 0600 "$TXN_DIR/unit-state"
  trap 'rollback $?' EXIT
  trap 'exit 130' INT TERM

  render_candidates "$template"
  if [[ -e "$SITE" ]]; then
    grep -Fq 'location ^~ /.well-known/acme-challenge/' "$SITE" ||
      fail "existing public IP site cannot serve ACME without a conflicting challenge server"
  else
    atomic_install "$TXN_DIR/challenge.conf" "$CHALLENGE_SITE" 0644
    CHALLENGE_ACTIVATED=true
    nginx -t
    systemctl reload nginx
  fi
  issue_certificates
  verify_certificate
  validate_nginx_candidate "$TXN_DIR/site.conf"

  atomic_install "$TXN_DIR/site.conf" "$SITE" 0644
  atomic_install "$TXN_DIR/hook" "$DEPLOY_HOOK" 0755
  [[ "$CHALLENGE_ACTIVATED" == false ]] || rm -f -- "$CHALLENGE_SITE"
  nginx -t
  systemctl reload nginx

  env_staged="$ENV_FILE.new.$$"
  STAGING_PATHS+=("$env_staged")
  install -o "$(stat -c %u "$ENV_FILE")" -g "$(stat -c %g "$ENV_FILE")" \
    -m "$(stat -c %a "$ENV_FILE")" "$TXN_DIR/joysong.env" "$env_staged"
  mv -Tf -- "$env_staged" "$ENV_FILE"
  systemctl restart "$SERVICE"
  wait_for_health
  verify_https_routes

  systemctl enable --now "$RENEWAL_TIMER"
  certbot renew --cert-name "$CERT_NAME" --dry-run --run-deploy-hooks \
    --no-random-sleep-on-renew >"$DRY_RUN_LOG" 2>&1
  nginx -t
  systemctl is-active --quiet "$RENEWAL_TIMER"
  systemctl is-enabled --quiet "$RENEWAL_TIMER"
  systemctl is-active --quiet "$SERVICE"
  wait_for_health
  verify_certificate
  verify_https_routes

  COMMITTED=true
  printf 'Public HTTPS endpoint enabled at https://%s (transaction backup: %s)\n' \
    "$EXPECTED_IP" "$TXN_DIR"
}

main "$@"
