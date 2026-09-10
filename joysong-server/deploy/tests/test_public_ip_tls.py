import unittest
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
            "location ^~ /assets/",
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
        self.assertIn("alias /var/www/joysong-demo/current/;", nginx)
        self.assertIn("try_files $uri $uri/ /admin/index.html;", nginx)
        self.assertNotIn("public-ip-ui", nginx)
        self.assertIn("location / {\n        return 404;", nginx)
        self.assertIn("alias /var/www/joysong-demo/current/assets/;", nginx)

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


if __name__ == "__main__":
    unittest.main()
