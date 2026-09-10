import unittest
import os
import shutil
import subprocess
import sys
from pathlib import Path


DEPLOY_ROOT = Path(__file__).resolve().parents[1]
SCRIPT = DEPLOY_ROOT / "host" / "enable-public-ip-tls.sh"
TEMPLATE = DEPLOY_ROOT / "nginx" / "joysong-public-ip.conf.template"


class PublicIpTlsDeploymentTest(unittest.TestCase):
    def test_certificate_workflow_uses_supported_ip_webroot_flow(self):
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("Certbot 5.4 or newer is required before setup", script)
        self.assertIn("--staging --preferred-profile shortlived --webroot", script)
        self.assertIn('--ip-address "$EXPECTED_IP"', script)
        self.assertIn("--register-unsafely-without-email", script)
        self.assertIn('certbot renew --cert-name "$CERT_NAME" --dry-run --run-deploy-hooks', script)
        self.assertIn("--no-random-sleep-on-renew", script)
        self.assertIn('openssl x509 -checkend 86400', script)
        self.assertIn('openssl x509 -checkip "$EXPECTED_IP" -noout', script)
        self.assertIn('>"$DRY_RUN_LOG" 2>&1', script)
        self.assertIn("snap.certbot.renew.timer", script)
        self.assertIn('systemctl enable --now "$RENEWAL_TIMER"', script)
        self.assertIn('certbot delete --cert-name "$STAGING_CERT_NAME"', script)
        self.assertIn('CERT_NAME="$EXPECTED_IP-$(basename "$TXN_DIR")"', script)
        self.assertIn("REUSE_CERT=true", script)
        self.assertIn('--cert-name "$CERT_NAME" --dry-run', script)
        self.assertIn('flock -n 9 || fail "another public IP TLS setup is already running"', script)
        self.assertNotIn("apt-get", script)
        self.assertNotIn("snap install", script)

    def test_public_ip_site_preserves_private_service_boundary(self):
        script = SCRIPT.read_text(encoding="utf-8")
        nginx = TEMPLATE.read_text(encoding="utf-8")
        self.assertIn("require_loopback_listener 8080", script)
        self.assertIn("require_loopback_listener 3306", script)
        self.assertNotIn("listen 8080", nginx)
        self.assertNotIn("listen 3306", nginx)
        self.assertIn("proxy_pass http://joysong_uat_backend;", nginx)
        self.assertIn("listen 443 ssl;", nginx)
        self.assertIn("ssl_certificate /etc/letsencrypt/live/__PUBLIC_IP__/fullchain.pem;", nginx)

    def test_ip_site_exposes_only_expected_public_routes(self):
        nginx = TEMPLATE.read_text(encoding="utf-8")
        for route in (
            "location ^~ /.well-known/acme-challenge/",
            "location = /admin",
            "location ^~ /admin/",
            "location = /actuator/health",
            "location = /api/auth/login",
            "location /api/",
            "location ^~ /images/",
            "location ^~ /s/diary/",
            "location ^~ /legal/",
        ):
            self.assertIn(route, nginx)
        self.assertIn("limit_req zone=joysong_uat_login burst=5 nodelay;", nginx)
        self.assertIn("client_max_body_size 52m;", nginx)
        self.assertIn("proxy_buffering off;", nginx)
        self.assertIn("return 301 https://__PUBLIC_IP__$request_uri;", nginx)
        self.assertIn("root /var/www/joysong-demo/current;", nginx)
        self.assertIn("try_files $uri $uri/ /admin/index.html;", nginx)
        self.assertNotIn("public-ip-ui", nginx)
        self.assertIn("location / {\n        return 404;", nginx)
        self.assertNotIn("location ^~ /assets/", nginx)

    def test_runtime_origins_change_without_exposing_private_ports(self):
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn('"SERVER_BASE_URL": f"https://{public_ip}"', script)
        self.assertIn('"APP_SHARE_BASE_URL": f"https://{public_ip}/s/diary/"', script)
        self.assertIn('public_origin = f"https://{public_ip}"', script)
        self.assertIn('origins.append(public_origin)', script)
        self.assertIn('required = set(updates) | {"CORS_ALLOWED_ORIGINS"}', script)
        self.assertIn('systemctl restart "$SERVICE"', script)
        self.assertIn("http://127.0.0.1:8080/actuator/health", script)

    def test_setup_is_transactional_and_restores_initial_state(self):
        script = SCRIPT.read_text(encoding="utf-8")
        for fragment in (
            'preflight "$template"',
            'TXN_DIR="$(mktemp -d /var/backups/joysong-public-ip-tls.XXXXXXXX)"',
            'track_file site "$SITE"',
            'track_file challenge "$CHALLENGE_SITE"',
            'track_file hook "$DEPLOY_HOOK"',
            'track_file env "$ENV_FILE"',
            'track_file dry_log "$DRY_RUN_LOG"',
            "trap 'rollback $?' EXIT",
            'restore_file env "$ENV_FILE"',
            'restore_file site "$SITE"',
            'restore_unit_state "$RENEWAL_TIMER"',
            'restore_unit_state "$SERVICE"',
            '>"$TXN_DIR/unit-state"',
            'STAGING_PATHS+=("$staged")',
            'STAGING_PATHS+=("$env_staged")',
            'validate_nginx_candidate "$TXN_DIR/challenge.conf"',
            'validate_nginx_candidate "$TXN_DIR/site.conf"',
        ):
            self.assertIn(fragment, script)
        self.assertLess(script.index('preflight "$template"'), script.index('TXN_DIR="$(mktemp'))
        self.assertLess(
            script.index('validate_nginx_candidate "$TXN_DIR/site.conf"'),
            script.index('atomic_install "$TXN_DIR/site.conf"'),
        )

    def test_success_is_committed_only_after_renewal_and_health_gates(self):
        script = SCRIPT.read_text(encoding="utf-8")
        main = script.index("main()")
        commit = script.index("COMMITTED=true", main)
        self.assertLess(script.index('certbot renew --cert-name "$CERT_NAME" --dry-run', main), commit)
        self.assertLess(script.rindex("wait_for_health"), commit)
        self.assertLess(script.rindex("verify_certificate"), commit)
        self.assertLess(script.rindex("verify_https_routes"), commit)
        self.assertIn('"https://$EXPECTED_IP/admin/"', script)
        self.assertIn('--max-time 10 --write-out', script)


class PublicIpTlsBehaviorTest(unittest.TestCase):
    def run_bash(self, body, main_fixture=False):
        bash = os.environ.get("JOYSONG_TEST_BASH") or shutil.which("bash")
        if os.name == "nt":
            git = shutil.which("git")
            candidate = Path(git).parent.parent / "bin/bash.exe" if git else Path("missing")
            if candidate.exists():
                bash = str(candidate)
        if not bash:
            self.skipTest("Bash is unavailable")
        source = SCRIPT.read_text(encoding="utf-8").rsplit('if [[ "${BASH_SOURCE[0]}"', 1)[0]
        # Redirect all certificate paths into a disposable fixture, never the host's /etc.
        source = source.replace('/etc/letsencrypt', '${TEST_CERT_ROOT}')
        prelude = 'export PATH=/usr/bin:$PATH\npython3() { "$TEST_PYTHON" "$@"; }\n'
        if main_fixture:
            source = source.replace('[[ "$EUID" -eq 0 ]]', '[[ true ]]')
            for path in ('/etc/nginx', '/etc/joysong-demo', '/var/backups', '/var/lib/joysong-deploy', '/var/log/letsencrypt'):
                source = source.replace(path, '${TEST_ROOT}' + path)
            prelude += 'TEST_ROOT=$(mktemp -d)\nTEST_CERT_ROOT="$TEST_ROOT/certificates"\ntrap \'rm -rf -- "$TEST_ROOT"\' EXIT\n'
        environment = dict(os.environ, TEST_PYTHON=sys.executable.replace('\\', '/'), TEST_CERT_ROOT='/unused-test-certificates', MSYS2_ARG_CONV_EXCL='/CN=test')
        result = subprocess.run([bash, "-s"], input=prelude + source + "\n" + body,
                                env=environment, text=True, encoding="utf-8", errors="replace", capture_output=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)

    def test_atomic_install_executes_under_nounset_and_cleans_staging(self):
        self.run_bash('''
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
install() { cp -- "$7" "$8"; }
printf data >"$work/source"
atomic_install "$work/source" "$work/target" 0600
[[ "$(cat "$work/target")" == data ]]
[[ ! -e "$work/target.new.$$" ]]
''')

    def test_version_rejects_empty_and_old_output(self):
        self.run_bash('''
certbot() { :; }
if version_at_least_5_4; then exit 1; fi
certbot() { printf 'certbot 5.3.0\\n'; }
if version_at_least_5_4; then exit 1; fi
certbot() { printf 'certbot 5.4.0\\n'; }
version_at_least_5_4
''')

    def test_main_transaction_failure_matrix_and_repeat(self):
        for failure in ('candidate', 'reload', 'renew', 'env_install', 'restart', 'marker_install', 'success'):
            with self.subTest(failure=failure):
                self.run_bash(r'''
mkdir -p "$(dirname "$SITE")" "$(dirname "$ENV_FILE")" "$(dirname "$LOCK_FILE")" \
  "$(dirname "$DEPLOY_HOOK")" "$(dirname "$DRY_RUN_LOG")" "$TEST_ROOT/var/backups"
printf 'location ^~ /.well-known/acme-challenge/ {}\nold site\n' >"$SITE"
printf 'old env\n' >"$ENV_FILE"
printf 'old hook\n' >"$DEPLOY_HOOK"
printf 'old log\n' >"$DRY_RUN_LOG"
cp "$SITE" "$TEST_ROOT/original-site"
failure=''' + failure + r'''
if [[ "$failure" == marker_install ]]; then printf 'old marker\n' >"$ENABLED_FILE"; fi
trip() {
  if [[ "$failure" == "$1" && ! -e "$TEST_ROOT/fired" ]]; then
    touch "$TEST_ROOT/fired"
    return 1
  fi
}
flock() { :; }
preflight() {
  SERVICE_ACTIVE=active; SERVICE_ENABLED=enabled
  RENEWAL_TIMER=certbot.timer; TIMER_ACTIVE=active; TIMER_ENABLED=enabled
}
select_certificate_name() { CERT_NAME=test; REUSE_CERT=true; }
render_candidates() {
  printf 'location ^~ /.well-known/acme-challenge/ {}\nnew site\n' >"$TXN_DIR/site.conf"
  printf 'new hook\n' >"$TXN_DIR/hook"
  printf 'new env\n' >"$TXN_DIR/joysong.env"
}
validate_nginx_candidate() { trip candidate; }
issue_certificates() { :; }
verify_certificate() { :; }
verify_https_routes() { :; }
wait_for_health() { printf 'healthy\n' >>"$TEST_ROOT/events"; }
nginx() { :; }
systemctl() {
  printf '%s\n' "$*" >>"$TEST_ROOT/events"
  if [[ "$1" == reload ]]; then trip reload
  elif [[ "$1" == restart ]]; then trip restart
  fi
}
certbot() { trip renew; }
install() {
  if [[ "$7" == "$TXN_DIR/joysong.env" ]]; then trip env_install || return 1; fi
  if [[ "$7" == "$TXN_DIR/enabled" ]]; then trip marker_install || return 1; fi
  cp -- "$7" "$8"
  chmod "$6" "$8"
}
# Run main outside an if/and/or condition so Bash errexit remains effective.
( main fixture ) &
child=$!
status=0
wait "$child" || status=$?
trap 'printf "Assertion failed: %s (status=%s)\\n" "$BASH_COMMAND" "$status" >&2' ERR
if [[ "$failure" == success ]]; then
  [[ "$status" == 0 ]]
  [[ "$(cat "$ENABLED_FILE")" == enabled ]]
  [[ "$(cat "$ENV_FILE")" == 'new env' ]]
  [[ "$(cat "$DEPLOY_HOOK")" == 'new hook' ]]
  cp "$TEST_ROOT/events" "$TEST_ROOT/first-events"
  ( main fixture ) &
  child=$!
  wait "$child"
  # Repeated enable must not restart an unchanged backend.
  [[ "$(grep -c '^restart joysong-demo.service' "$TEST_ROOT/events")" == 1 ]]
else
  [[ "$status" == 1 ]]
  cmp "$SITE" "$TEST_ROOT/original-site"
  [[ "$(cat "$ENV_FILE")" == 'old env' ]]
  [[ "$(cat "$DEPLOY_HOOK")" == 'old hook' ]]
  [[ "$(cat "$DRY_RUN_LOG")" == 'old log' ]]
  if [[ "$failure" == marker_install ]]; then
    [[ "$(cat "$ENABLED_FILE")" == 'old marker' ]]
  else [[ ! -e "$ENABLED_FILE" ]]; fi
  [[ ! -e "$CHALLENGE_SITE" ]]
  [[ -z "$(find "$TEST_ROOT" -name '*.new.*' -o -name '*.rollback.*')" ]]
  [[ "$(tail -1 "$TEST_ROOT/events")" == healthy ]]
fi
''', main_fixture=True)

    def test_real_certificate_key_and_certbot_renewal_config(self):
        self.run_bash('''
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
TEST_CERT_ROOT="$work"
renew_root="$work"
if command -v cygpath >/dev/null; then renew_root=$(cygpath -m "$work"); fi
mkdir -p "$work/live/test" "$work/renewal"
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj /CN=test \\
  -addext "subjectAltName=IP:$EXPECTED_IP" -keyout "$work/live/test/privkey.pem" \\
  -out "$work/live/test/fullchain.pem" >/dev/null 2>&1
# Real Certbot configobj nested section syntax must remain parseable.
printf 'version = 5.4.0\\nfullchain = %s/live/test/fullchain.pem\\nprivkey = %s/live/test/privkey.pem\\n[renewalparams]\\nauthenticator = webroot\\n[[webroot_map]]\\n%s = /var/lib/letsencrypt\\n' \\
  "$renew_root" "$renew_root" "$EXPECTED_IP" >"$work/renewal/test.conf"
certificate_usable test
mv "$work/renewal/test.conf" "$work/renewal/saved"
if certificate_usable test; then exit 1; fi
mv "$work/renewal/saved" "$work/renewal/test.conf"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$work/live/test/privkey.pem" >/dev/null 2>&1
if certificate_usable test; then exit 1; fi
''')

    @unittest.skipIf(os.name == 'nt', 'Unix release permissions and flock require Linux')
    def test_release_assets_and_deployment_lock(self):
        self.run_bash('''
work=$(mktemp -d)
trap 'chmod -R u+w "$work"; rm -rf -- "$work"' EXIT
mkdir -p "$work/release/assets" "$work/release/admin/assets"
printf '<script src="/assets/app.js"></script>' >"$work/release/index.html"
printf '<script src="/admin/assets/app.js"></script>' >"$work/release/admin/index.html"
touch "$work/release/assets/app.js" "$work/release/admin/assets/app.js"
chmod -R go-w "$work/release"
require_admin_release "$work/release"
rm "$work/release/admin/assets/app.js"
if require_admin_release "$work/release"; then exit 1; fi
ln -s "$work/release/assets/app.js" "$work/release/admin/assets/app.js"
if require_admin_release "$work/release"; then exit 1; fi
exec 8>"$work/deploy.lock"
flock -n 8
if (exec 9>"$work/deploy.lock"; flock -n 9); then exit 1; fi
''')

    def test_atomic_install_failure_preserves_target(self):
        self.run_bash('''
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
printf old >"$work/target"
if (install() { return 1; }; atomic_install missing "$work/target" 0600); then exit 1; fi
[[ "$(cat "$work/target")" == old ]]
[[ ! -e "$work/target.new.$$" ]]
''')

    def test_health_wait_handles_delayed_start_and_deadline(self):
        self.run_bash('''
attempt=0
curl() { attempt=$((attempt + 1)); ((attempt >= 3)); }
sleep() { :; }
wait_for_health
[[ "$attempt" == 3 ]]
curl() { return 1; }
sleep() { SECONDS=$((SECONDS + 100)); }
if wait_for_health; then exit 1; fi
''')

    def test_rollback_restarts_only_changed_service_and_waits_after_restore(self):
        for changed in ("false", "true"):
            with self.subTest(changed=changed):
                self.run_bash('''
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
TXN_DIR="$work"
SERVICE_ACTIVE=active
restore_file() { printf 'restore %s\\n' "$1" >>"$work/events"; }
restore_unit_state() { printf 'unit %s\\n' "$1" >>"$work/events"; }
nginx() { :; }
wait_for_health() { printf 'health\\n' >>"$work/events"; }
SERVICE_CHANGED=''' + changed + '''
if (rollback 2); then exit 1; else [[ "$?" == 2 ]]; fi
[[ "$(tail -1 "$work/events")" == health ]]
grep -q 'restore enabled' "$work/events"
''' + ('''grep -q 'unit joysong-demo.service' "$work/events"
''' if changed == "true" else '''! grep -q '^unit ' "$work/events"
'''))


if __name__ == "__main__":
    unittest.main()
