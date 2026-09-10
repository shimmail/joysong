#!/bin/bash
set -Eeuo pipefail
umask 077

readonly EXPECTED_IP=121.41.230.98
readonly WEBROOT=/var/lib/letsencrypt
readonly SITE=/etc/nginx/conf.d/joysong-public-ip.conf
readonly CHALLENGE_SITE=/etc/nginx/conf.d/joysong-public-ip-challenge.conf
readonly DEPLOY_HOOK=/etc/letsencrypt/renewal-hooks/deploy/joysong-nginx-reload
readonly ENV_FILE=/etc/joysong-demo/joysong.env
readonly LOCK_FILE=/run/lock/joysong-public-ip-tls.lock
readonly DRY_RUN_LOG=/var/log/letsencrypt/joysong-renew-dry-run.log

fail() { printf 'JoySong public IP TLS setup failed: %s\n' "$*" >&2; exit 2; }

version_at_least_5_4() {
  certbot --version 2>&1 | sed -n 's/^certbot \([0-9][0-9.]*\)$/\1/p' | awk -F. '{ exit !($1 > 5 || ($1 == 5 && $2 >= 4)) }'
}

require_loopback_listener() {
  local port="$1" lines
  lines="$(ss -H -ltn "sport = :$port")"
  [[ -n "$lines" ]] || fail "TCP $port is not listening"
  if printf '%s\n' "$lines" | awk '{print $4}' | grep -Ev '^(127\.0\.0\.1|\[::ffff:127\.0\.0\.1\]):' >/dev/null; then
    fail "TCP $port must remain bound only to 127.0.0.1"
  fi
}

install_certbot() {
  if command -v certbot >/dev/null && version_at_least_5_4; then return; fi
  apt-get update
  apt-get install -y --no-install-recommends snapd
  snap list core >/dev/null 2>&1 || snap install core >/dev/null
  snap refresh core >/dev/null
  snap list certbot >/dev/null 2>&1 || snap install --classic certbot
  ln -sfn /snap/bin/certbot /usr/local/bin/certbot
  version_at_least_5_4 || fail "Certbot 5.4 or newer is required"
}

write_challenge_site() {
  install -d -o root -g root -m 0755 "$WEBROOT/.well-known/acme-challenge"
  cat >"$CHALLENGE_SITE" <<EOF
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
  chmod 0644 "$CHALLENGE_SITE"
  nginx -t
  systemctl reload nginx
}

issue_certificates() {
  if [[ -f "/etc/letsencrypt/live/$EXPECTED_IP/fullchain.pem" ]] &&
     openssl x509 -checkend 86400 -noout -in "/etc/letsencrypt/live/$EXPECTED_IP/fullchain.pem" >/dev/null &&
     openssl x509 -noout -ext subjectAltName -in "/etc/letsencrypt/live/$EXPECTED_IP/fullchain.pem" |
       grep -F "IP Address:$EXPECTED_IP" >/dev/null; then
    return
  fi
  certbot certonly --staging --preferred-profile shortlived --webroot --webroot-path "$WEBROOT" \
    --ip-address "$EXPECTED_IP" --cert-name "$EXPECTED_IP-staging" --non-interactive --agree-tos --register-unsafely-without-email
  certbot certonly --preferred-profile shortlived --webroot --webroot-path "$WEBROOT" \
    --ip-address "$EXPECTED_IP" --cert-name "$EXPECTED_IP" --non-interactive --agree-tos --register-unsafely-without-email
  certbot delete --cert-name "$EXPECTED_IP-staging" --non-interactive
}

update_public_origins() {
  [[ -f "$ENV_FILE" && ! -L "$ENV_FILE" ]] || fail "runtime environment file is unavailable or unsafe"
  python3 -I - "$ENV_FILE" "$EXPECTED_IP" <<'PY'
import os
import stat
import sys
import tempfile

path, public_ip = sys.argv[1:]
details = os.stat(path, follow_symlinks=False)
if not stat.S_ISREG(details.st_mode) or details.st_nlink != 1:
    raise SystemExit("runtime environment file must be one regular file")

updates = {
    "SERVER_BASE_URL": f"https://{public_ip}",
    "APP_SHARE_BASE_URL": f"https://{public_ip}/s/diary/",
}
public_origin = f"https://{public_ip}"
seen = set()
lines = []
with open(path, encoding="utf-8") as source:
    for raw in source:
        key = raw.split("=", 1)[0] if "=" in raw else ""
        if key == "CORS_ALLOWED_ORIGINS":
            existing = raw.rstrip("\r\n").split("=", 1)[1]
            origins = [value.strip() for value in existing.split(",") if value.strip()]
            if public_origin not in origins:
                origins.append(public_origin)
            lines.append(f"{key}={','.join(origins)}\n")
            seen.add(key)
            continue
        if key in updates:
            lines.append(f"{key}={updates[key]}\n")
            seen.add(key)
        else:
            lines.append(raw)
required = set(updates) | {"CORS_ALLOWED_ORIGINS"}
if seen != required:
    raise SystemExit("runtime environment file is missing required public origin keys")

fd, staged = tempfile.mkstemp(prefix=".joysong.env.", dir=os.path.dirname(path))
try:
    with os.fdopen(fd, "w", encoding="utf-8", newline="") as target:
        target.writelines(lines)
        target.flush()
        os.fsync(target.fileno())
    os.chown(staged, details.st_uid, details.st_gid)
    os.chmod(staged, stat.S_IMODE(details.st_mode))
    os.replace(staged, path)
finally:
    if os.path.exists(staged):
        os.unlink(staged)
PY
}

install_site_and_renewal_hook() {
  local template="$1" staged renewal_timer
  [[ -f "$template" && ! -L "$template" ]] || fail "Nginx template is unavailable or unsafe"
  staged="${SITE}.new.$$"
  sed "s/__PUBLIC_IP__/$EXPECTED_IP/g" "$template" >"$staged"
  chmod 0644 "$staged"
  mv -Tf "$staged" "$SITE"
  rm -f -- "$CHALLENGE_SITE"
  install -d -o root -g root -m 0755 "$(dirname "$DEPLOY_HOOK")"
  cat >"$DEPLOY_HOOK" <<'EOF'
#!/bin/sh
set -eu
nginx -t
systemctl reload nginx
EOF
  chmod 0755 "$DEPLOY_HOOK"
  nginx -t
  systemctl reload nginx
  if systemctl list-unit-files certbot.timer --no-legend 2>/dev/null | grep -q '^certbot.timer'; then
    renewal_timer=certbot.timer
  elif systemctl list-unit-files snap.certbot.renew.timer --no-legend 2>/dev/null | grep -q '^snap.certbot.renew.timer'; then
    renewal_timer=snap.certbot.renew.timer
  else
    fail "Certbot renewal timer is unavailable"
  fi
  systemctl enable --now "$renewal_timer"
  certbot renew --dry-run --run-deploy-hooks --no-random-sleep-on-renew \
    >"$DRY_RUN_LOG" 2>&1
  update_public_origins
  systemctl restart joysong-demo.service
  curl --retry 30 --retry-connrefused --retry-delay 2 --max-time 5 \
    --fail --silent --show-error http://127.0.0.1:8080/actuator/health >/dev/null
}

main() {
  [[ "$EUID" -eq 0 ]] || fail "run as root"
  (($# == 1)) || fail "usage: enable-public-ip-tls.sh NGINX_TEMPLATE"
  exec 9>"$LOCK_FILE"
  flock -n 9 || fail "another public IP TLS setup is already running"
  require_loopback_listener 8080
  require_loopback_listener 3306
  install_certbot
  write_challenge_site
  issue_certificates
  install_site_and_renewal_hook "$1"
  printf 'Public HTTPS endpoint enabled at https://%s\n' "$EXPECTED_IP"
}

main "$@"
