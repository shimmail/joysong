import unittest
from pathlib import Path


DEPLOY_ROOT = Path(__file__).resolve().parents[1]
SCRIPT = DEPLOY_ROOT / "host" / "enable-public-ip-tls.sh"
TEMPLATE = DEPLOY_ROOT / "nginx" / "joysong-public-ip.conf.template"


class PublicIpTlsDeploymentTest(unittest.TestCase):
    def test_certificate_workflow_uses_supported_ip_webroot_flow(self):
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("Certbot 5.4 or newer is required", script)
        self.assertIn("--staging --preferred-profile shortlived --webroot", script)
        self.assertIn('--ip-address "$EXPECTED_IP"', script)
        self.assertIn("--register-unsafely-without-email", script)
        self.assertIn("certbot renew --dry-run --run-deploy-hooks --no-random-sleep-on-renew", script)
        self.assertIn('openssl x509 -checkend 86400', script)
        self.assertIn('grep -F "IP Address:$EXPECTED_IP"', script)
        self.assertIn('>"$DRY_RUN_LOG" 2>&1', script)
        self.assertIn("snap.certbot.renew.timer", script)
        self.assertIn('systemctl enable --now "$renewal_timer"', script)
        self.assertIn('certbot delete --cert-name "$EXPECTED_IP-staging"', script)
        self.assertIn('flock -n 9 || fail "another public IP TLS setup is already running"', script)

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
        self.assertNotIn("/var/www/joysong-demo/current", nginx)

    def test_runtime_origins_change_without_exposing_private_ports(self):
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn('"SERVER_BASE_URL": f"https://{public_ip}"', script)
        self.assertIn('"APP_SHARE_BASE_URL": f"https://{public_ip}/s/diary/"', script)
        self.assertIn('public_origin = f"https://{public_ip}"', script)
        self.assertIn('origins.append(public_origin)', script)
        self.assertIn('required = set(updates) | {"CORS_ALLOWED_ORIGINS"}', script)
        self.assertIn("systemctl restart joysong-demo.service", script)
        self.assertIn("http://127.0.0.1:8080/actuator/health", script)


if __name__ == "__main__":
    unittest.main()
