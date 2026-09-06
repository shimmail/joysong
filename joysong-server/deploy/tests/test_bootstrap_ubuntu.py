from __future__ import print_function

import os
import shutil
import subprocess
import textwrap
import unittest
from pathlib import Path


DEPLOY_ROOT = Path(__file__).resolve().parents[1]
BOOTSTRAP = DEPLOY_ROOT / "host" / "bootstrap-uat-host.sh"
SYSTEMD_TEMPLATE = DEPLOY_ROOT / "systemd" / "joysong-demo.service"
NGINX_TEMPLATE = DEPLOY_ROOT / "nginx" / "joysong-public.conf"


def find_gnu_bash():
    candidates = []
    git = shutil.which("git")
    if git:
        git_root = Path(git).resolve().parent.parent
        candidates.extend([git_root / "bin" / "bash.exe", git_root / "usr" / "bin" / "bash.exe"])
    bash = shutil.which("bash")
    if bash:
        candidates.append(Path(bash))
    for candidate in candidates:
        if not candidate.is_file():
            continue
        try:
            result = subprocess.run(
                [str(candidate), "--version"],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                encoding="utf-8",
                errors="replace",
                timeout=10,
            )
        except (OSError, subprocess.SubprocessError):
            continue
        if result.returncode == 0 and "GNU bash" in result.stdout:
            return str(candidate)
    return None


GNU_BASH = find_gnu_bash()


class UbuntuBootstrapTest(unittest.TestCase):
    maxDiff = None

    def run_bash(self, body):
        if GNU_BASH is None:
            self.skipTest("GNU Bash is not available")
        bash_path = Path(GNU_BASH).resolve()
        git_usr_bin = (
            bash_path.parent
            if bash_path.parent.name.lower() == "bin" and bash_path.parent.parent.name.lower() == "usr"
            else bash_path.parent.parent / "usr" / "bin"
        )
        environment = os.environ.copy()
        environment["PATH"] = str(git_usr_bin) + os.pathsep + environment.get("PATH", "")
        result = subprocess.run(
            [GNU_BASH, "-c", textwrap.dedent(body)],
            cwd=str(DEPLOY_ROOT.parents[1]),
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            encoding="utf-8",
            errors="replace",
            timeout=60,
        )
        if result.returncode == 77:
            self.skipTest("fixture requires an unprivileged GNU Bash")
        if result.returncode != 0:
            self.fail(
                "bash fixture failed with {}\nstdout:\n{}\nstderr:\n{}".format(
                    result.returncode, result.stdout, result.stderr
                )
            )
        return result

    @staticmethod
    def fixture_preamble():
        return r"""
            set -Eeuo pipefail
            if [[ "$EUID" -eq 0 ]]; then exit 77; fi
            fixture="$(mktemp -d)"
            cleanup_fixture() { rm -rf -- "$fixture"; }
            trap cleanup_fixture EXIT
            mkdir -p "$fixture/etc" "$fixture/run"
            printf 'ID=ubuntu\nVERSION_ID="24.04"\n' >"$fixture/etc/os-release"
            printf 'x86_64\n' >"$fixture/run/joysong-bootstrap-arch"
            printf '255\n' >"$fixture/run/joysong-bootstrap-systemd-version"
            export JOYSONG_UAT_BOOTSTRAP_TESTING=1
            export JOYSONG_UAT_BOOTSTRAP_TEST_ROOT="$fixture"
            bootstrap="$PWD/joysong-server/deploy/host/bootstrap-uat-host.sh"
        """

    @staticmethod
    def apply_fixture():
        return r"""
            source_dir="$PWD/joysong-server/deploy"
            secrets="$fixture/secrets"
            payload="$fixture/runner-payload"
            archive="$fixture/actions-runner-linux-x64.tar.gz"
            mkdir -p "$secrets" "$payload/bin"
            cat >"$secrets/joysong.env" <<'ENV'
SPRING_PROFILES_ACTIVE=demo
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8080
DB_URL=jdbc:mysql://127.0.0.1:3306/myapp_worktree_uat?useSSL=false
DB_USERNAME=joysong_app
DB_PASSWORD=not-a-real-db-password-value
DEMO_DATABASE_NAME=myapp_worktree_uat
JWT_SECRET=not-a-real-jwt-secret-value-000000
ADMIN_PHONE=13800000000
ADMIN_PASSWORD=not-a-real-admin-password-value
ENV
            cat >"$secrets/joysong-uat-backup.cnf" <<'CNF'
[client]
host=127.0.0.1
port=3306
protocol=TCP
user=joysong_backup
password=not-a-real-backup-password-value
database=myapp_worktree_uat
CNF
            printf 'not-a-real-registration-token-value\n' >"$secrets/runner-registration-token"
            chmod 0600 "$secrets/joysong.env" "$secrets/joysong-uat-backup.cnf" "$secrets/runner-registration-token"
            cat >"$payload/bin/Runner.Listener" <<'RUNNER'
#!/bin/sh
printf '2.337.0\n'
RUNNER
            cat >"$payload/config.sh" <<'CONFIG'
#!/bin/sh
set -eu
label=''
name=''
repository=''
work=''
no_defaults=false
disable_update=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --labels) shift; label="$1" ;;
    --name) shift; name="$1" ;;
    --url) shift; repository="$1" ;;
    --work) shift; work="$1" ;;
    --no-default-labels) no_defaults=true ;;
    --disableupdate) disable_update=true ;;
    --token|--unattended) exit 1 ;;
  esac
  shift
done
[ "$label" = joysong-uat-deploy ]
[ "$no_defaults" = true ]
[ "$disable_update" = true ]
IFS= read -r token
[ "$token" = not-a-real-registration-token-value ]
printf '{"agentId":1,"agentName":"%s","gitHubUrl":"%s","workFolder":"%s","disableUpdate":true}\n' "$name" "$repository" "$work" >.runner
CONFIG
            cat >"$payload/runsvc.sh" <<'SERVICE'
#!/bin/sh
exit 0
SERVICE
            chmod 0755 "$payload/bin/Runner.Listener" "$payload/config.sh" "$payload/runsvc.sh"
            tar -czf "$archive" -C "$payload" .
            runner_sha="$(sha256sum "$archive" | awk '{print $1}')"
        """

    def test_templates_and_production_constants_are_fixed(self):
        source = BOOTSTRAP.read_text(encoding="utf-8")
        unit = SYSTEMD_TEMPLATE.read_text(encoding="utf-8")
        nginx = NGINX_TEMPLATE.read_text(encoding="utf-8")
        for fragment in (
            "EXPECTED_RUNNER_VERSION=2.337.0",
            "70920811a4f8ad4328818682bca5c6469c1c942fab52448868071d0063816613",
            "DATABASE_NAME=myapp_worktree_uat",
            "NGINX_USER=www-data",
            '[[ "${JOYSONG_UAT_BOOTSTRAP_TESTING:-}" == 1 && "$EUID" -ne 0 ]]',
            "SYSTEMD_UNIT_SHA256=%s\\nNGINX_SITE_SHA256=%s\\n",
            "--no-default-labels",
            'tarfile.open(sys.argv[1], mode="r|gz")',
            "member.isdev() or member.isfifo() or member.issym() or member.islnk()",
            "GRANT SHOW_ROUTINE ON *.*",
            '[[ "$global_privileges" == SHOW_ROUTINE ]]',
        ):
            self.assertIn(fragment, source)
        self.assertIn(
            "ExecStart=/usr/bin/java -Xms256m -Xmx896m -XX:+UseG1GC "
            "-XX:+ExitOnOutOfMemoryError -jar /opt/joysong-demo/current/joysong-server.jar",
            unit,
        )
        self.assertIn("WorkingDirectory=/opt/joysong-demo/current", unit)
        self.assertIn("EnvironmentFile=/etc/joysong-demo/joysong.env", unit)
        self.assertIn("server 127.0.0.1:8080;", nginx)
        self.assertIn("root /var/www/joysong-demo/current;", nginx)

    def test_preflight_accepts_only_blank_ubuntu_2404_systemd_255_x64(self):
        self.run_bash(
            self.fixture_preamble()
            + r"""
            "$bootstrap" preflight | grep -q 'fresh-host preflight passed'
            sed -i 's/ID=ubuntu/ID=debian/' "$fixture/etc/os-release"
            if "$bootstrap" preflight >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'host must be Ubuntu' "$fixture/out"
            sed -i 's/ID=debian/ID=ubuntu/' "$fixture/etc/os-release"
            printf 'aarch64\n' >"$fixture/run/joysong-bootstrap-arch"
            if "$bootstrap" preflight >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'architecture must be x86_64' "$fixture/out"
            printf 'x86_64\n' >"$fixture/run/joysong-bootstrap-arch"
            printf '254\n' >"$fixture/run/joysong-bootstrap-systemd-version"
            if "$bootstrap" preflight >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'must use systemd 255' "$fixture/out"
            : >"$fixture/run/joysong-bootstrap-systemd-version"
            if "$bootstrap" preflight >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'must use systemd 255' "$fixture/out"
            """
        )

    def test_production_path_rejects_non_root(self):
        self.run_bash(
            r"""
            set -Eeuo pipefail
            if [[ "$EUID" -eq 0 ]]; then exit 77; fi
            unset JOYSONG_UAT_BOOTSTRAP_TESTING JOYSONG_UAT_BOOTSTRAP_TEST_ROOT
            bootstrap="$PWD/joysong-server/deploy/host/bootstrap-uat-host.sh"
            if "$bootstrap" preflight >bootstrap-non-root.out 2>&1; then
              rm -f bootstrap-non-root.out
              exit 1
            fi
            grep -q 'run as root' bootstrap-non-root.out
            rm -f bootstrap-non-root.out
            """
        )

    def test_production_listener_inspection_accepts_only_verified_resolved_dns(self):
        self.run_bash(
            self.fixture_preamble()
            + r"""
            source <(sed '$d' "$bootstrap")
            ss() { cat "$fixture/listeners"; }
            systemctl() { printf '43\n'; }
            executable=/usr/lib/systemd/systemd-resolved
            readlink() { printf '%s\n' "$executable"; }
            cat >"$fixture/listeners" <<'LISTENERS'
LISTEN 0 4096 127.0.0.53%lo:53 0.0.0.0:* users:(("systemd-resolve",pid=43,fd=14))
LISTEN 0 4096 127.0.0.54:53 0.0.0.0:* users:(("systemd-resolve",pid=43,fd=16))
LISTEN 0 4096 0.0.0.0:22 0.0.0.0:* users:(("sshd",pid=71,fd=3))
LISTEN 0 4096 [::]:22 [::]:* users:(("sshd",pid=71,fd=4))
LISTENERS
            validate_tcp_listeners
            sed -i 's/127.0.0.54:53/0.0.0.0:53/' "$fixture/listeners"
            if (validate_tcp_listeners) >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'unexpected listening TCP port' "$fixture/out"
            sed -i 's/0.0.0.0:53/127.0.0.54:53/; s/pid=43/pid=44/g' "$fixture/listeners"
            if (validate_tcp_listeners) >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'does not belong to systemd-resolved' "$fixture/out"
            sed -i 's/pid=44/pid=43/g' "$fixture/listeners"
            executable=/tmp/unrecognized-dns
            if (validate_tcp_listeners) >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'unexpected executable' "$fixture/out"
            executable=/usr/lib/systemd/systemd-resolved
            printf 'LISTEN 0 100 127.0.0.1:9000 0.0.0.0:* users:(("unknown",pid=99,fd=3))\n' >>"$fixture/listeners"
            if (validate_tcp_listeners) >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'unexpected listening TCP port' "$fixture/out"
            """
        )

    def test_production_mysql_version_detection_accepts_ubuntu_80_only(self):
        self.run_bash(
            self.fixture_preamble()
            + r"""
            source <(sed '$d' "$bootstrap")
            client_version='mysql  Ver 8.0.43-0ubuntu0.24.04.1 for Linux on x86_64 ((Ubuntu))'
            server_version='/usr/sbin/mysqld  Ver 8.0.43-0ubuntu0.24.04.1 for Linux on x86_64 ((Ubuntu))'
            mysql() { printf '%s\n' "$client_version"; }
            mysqld() { printf '%s\n' "$server_version"; }
            verify_mysql_versions
            client_version='mysql  Ver 15.1 Distrib 10.11.8-MariaDB, for debian-linux-gnu (x86_64)'
            if (verify_mysql_versions) >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'MySQL 8 client is unavailable' "$fixture/out"
            client_version='mysql  Ver 8.4.0 for Linux on x86_64 (MySQL Community Server - GPL)'
            if (verify_mysql_versions) >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'MySQL 8 client is unavailable' "$fixture/out"
            client_version='mysql  Ver 8.0.43 for Linux on x86_64 (MySQL Community Server - GPL)'
            server_version='mysqld  Ver 8.4.0 for Linux on x86_64 (MySQL Community Server - GPL)'
            if (verify_mysql_versions) >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'MySQL 8 server is unavailable' "$fixture/out"
            """
        )

    def test_production_mysql_initial_state_allows_only_packaged_local_accounts(self):
        self.run_bash(
            self.fixture_preamble()
            + r"""
            source <(sed '$d' "$bootstrap")
            scenario=packaged
            mysql() {
              "$PYTHON_BIN" -I - "$scenario" "$@" <<'PY'
import sqlite3, sys
scenario = sys.argv[1]
query = next(value.split('=', 1)[1] for value in sys.argv[2:] if value.startswith('--execute='))
database = sqlite3.connect(':memory:')
database.execute("ATTACH DATABASE ':memory:' AS mysql")
database.execute('CREATE TABLE mysql.user (User TEXT, Host TEXT)')
database.executemany('INSERT INTO mysql.user VALUES (?, ?)',
                    [(name, 'localhost') for name in
                     ('root', 'debian-sys-maint', 'mysql.infoschema', 'mysql.session', 'mysql.sys')])
database.execute("ATTACH DATABASE ':memory:' AS information_schema")
database.execute('CREATE TABLE information_schema.SCHEMATA (SCHEMA_NAME TEXT)')
database.executemany('INSERT INTO information_schema.SCHEMATA VALUES (?)',
                    [(name,) for name in ('information_schema', 'mysql', 'performance_schema', 'sys')])
if scenario == 'unknown_account':
    database.execute("INSERT INTO mysql.user VALUES ('unknown', 'localhost')")
elif scenario == 'maintenance_remote':
    database.execute("INSERT INTO mysql.user VALUES ('debian-sys-maint', '%')")
elif scenario == 'internal_remote':
    database.execute("INSERT INTO mysql.user VALUES ('mysql.sys', '127.0.0.1')")
elif scenario == 'business_schema':
    database.execute("INSERT INTO information_schema.SCHEMATA VALUES ('myapp_worktree_existing')")
for row in database.execute(query):
    print(row[0])
database.close()
PY
            }
            validate_mysql_initial_state
            for scenario in unknown_account maintenance_remote internal_remote; do
              if (validate_mysql_initial_state) >"$fixture/out" 2>&1; then exit 1; fi
              grep -q 'unexpected account' "$fixture/out"
            done
            scenario=business_schema
            if (validate_mysql_initial_state) >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'unexpected non-system database' "$fixture/out"
            """
        )

    @unittest.skipUnless(os.name == "posix", "production hidden-input transport requires Linux PTY")
    def test_production_runner_registration_uses_hidden_pty_and_rejects_retries(self):
        self.run_bash(
            self.fixture_preamble()
            + r"""
            source <(sed '$d' "$bootstrap")
            mkdir -p "$RUNNER_ROOT_PATH" "$RUNNER_HOME_PATH"
            printf 'not-a-real-registration-token-value\n' >"$fixture/token"
            chmod 0600 "$fixture/token"
            cat >"$RUNNER_ROOT_PATH/config.sh" <<'CONFIG'
#!/usr/bin/python3
import os, pathlib, sys, termios
token = b'not-a-real-registration-token-value'
assert '--token' not in sys.argv and '--unattended' not in sys.argv
assert '--disableupdate' in sys.argv
assert token not in pathlib.Path('/proc/self/cmdline').read_bytes()
assert token not in pathlib.Path('/proc/self/environ').read_bytes()
assert os.isatty(0)
assert not termios.tcgetattr(0)[3] & (termios.ECHO | termios.ECHONL)
assert sys.argv[sys.argv.index('--runnergroup') + 1] == 'Default'
print('What is your runner register token? ', end='', flush=True)
assert sys.stdin.buffer.readline().rstrip(b'\r\n') == token
mode = pathlib.Path('mode').read_text().strip()
if mode == 'retry':
    print('What is your runner register token? ', end='', flush=True)
    sys.stdin.buffer.readline()
    raise SystemExit(1)
if mode == 'fail':
    print(token.decode(), flush=True)
    raise SystemExit(1)
pathlib.Path('registered').touch()
CONFIG
            chmod 0755 "$RUNNER_ROOT_PATH/config.sh"
            printf 'success\n' >"$RUNNER_ROOT_PATH/mode"
            register_runner_with_token "$fixture/token" >"$fixture/out" 2>&1
            test -f "$RUNNER_ROOT_PATH/registered"
            test ! -s "$fixture/out"
            rm "$RUNNER_ROOT_PATH/registered"
            printf 'retry\n' >"$RUNNER_ROOT_PATH/mode"
            if (register_runner_with_token "$fixture/token") >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'runner hidden-input registration failed' "$fixture/out"
            test ! -f "$RUNNER_ROOT_PATH/registered"
            printf 'fail\n' >"$RUNNER_ROOT_PATH/mode"
            if (register_runner_with_token "$fixture/token") >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'runner hidden-input registration failed' "$fixture/out"
            if grep -q 'not-a-real-registration-token-value' "$fixture/out"; then exit 1; fi
            """
        )

    def test_missing_or_invalid_first_admin_is_rejected_before_initialization(self):
        self.run_bash(
            self.fixture_preamble()
            + self.apply_fixture()
            + r"""
            source <(sed '$d' "$bootstrap")
            validate_environment_file "$secrets/joysong.env"
            sed -i '/^ADMIN_PASSWORD=/d' "$secrets/joysong.env"
            if (validate_environment_file "$secrets/joysong.env") >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'requires ADMIN_PASSWORD' "$fixture/out"
            printf 'ADMIN_PASSWORD=short\n' >>"$secrets/joysong.env"
            if (validate_environment_file "$secrets/joysong.env") >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'requires ADMIN_PASSWORD' "$fixture/out"
            sed -i 's/ADMIN_PASSWORD=short/ADMIN_PASSWORD=not-a-real-admin-password-value/; s/ADMIN_PHONE=13800000000/ADMIN_PHONE=invalid/' "$secrets/joysong.env"
            if (validate_environment_file "$secrets/joysong.env") >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'requires a valid ADMIN_PHONE' "$fixture/out"
            sed -i 's/ADMIN_PHONE=invalid/ADMIN_PHONE=+8613800000000/' "$secrets/joysong.env"
            if (validate_environment_file "$secrets/joysong.env") >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'requires a valid ADMIN_PHONE' "$fixture/out"
            sed -i 's/ADMIN_PHONE=+8613800000000/ADMIN_PHONE=13800000000/; s/ADMIN_PASSWORD=not-a-real-admin-password-value/ADMIN_PASSWORD= not-a-real-admin-password-value/' "$secrets/joysong.env"
            if (validate_environment_file "$secrets/joysong.env") >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'leading or trailing whitespace' "$fixture/out"
            "$PYTHON_BIN" -I - "$secrets/joysong.env" <<'PY'
import sys
path = sys.argv[1]
with open(path, encoding='utf-8') as stream:
    lines = [line for line in stream if not line.startswith('ADMIN_PASSWORD=')]
with open(path, 'w', encoding='utf-8') as stream:
    stream.writelines(lines)
    stream.write('ADMIN_PASSWORD=' + '\U0001f512' * 6 + '\n')
PY
            validate_environment_file "$secrets/joysong.env"
            test ! -e "$fixture/run/joysong-bootstrap-packages"
            """
        )

    def test_preflight_rejects_ports_and_preexisting_resources(self):
        self.run_bash(
            self.fixture_preamble()
            + r"""
            : >"$fixture/run/joysong-bootstrap-port-8080-busy"
            if "$bootstrap" preflight >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'TCP port 8080 is already in use' "$fixture/out"
            rm "$fixture/run/joysong-bootstrap-port-8080-busy"
            mkdir -p "$fixture/etc/joysong-demo"
            : >"$fixture/etc/joysong-demo/joysong.env"
            if "$bootstrap" preflight >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'fresh host path already exists' "$fixture/out"
            """
        )

    def test_apply_initializes_once_and_second_apply_only_verifies(self):
        self.run_bash(
            self.fixture_preamble()
            + self.apply_fixture()
            + r"""
            "$bootstrap" apply "$source_dir" "$secrets" "$archive" 2.337.0 "$runner_sha" >"$fixture/first.out"
            test ! -e "$secrets/runner-registration-token" || { echo 'token was not consumed' >&2; exit 1; }
            test -f "$fixture/etc/joysong-demo/host-contract" || { echo 'host contract missing' >&2; exit 1; }
            test "$(wc -l <"$fixture/etc/joysong-demo/host-contract" | tr -d ' ')" = 2 || { echo 'host contract line count' >&2; exit 1; }
            grep -Eq '^SYSTEMD_UNIT_SHA256=[0-9a-f]{64}$' "$fixture/etc/joysong-demo/host-contract" || { echo 'systemd contract hash missing' >&2; exit 1; }
            grep -Eq '^NGINX_SITE_SHA256=[0-9a-f]{64}$' "$fixture/etc/joysong-demo/host-contract" || { echo 'nginx contract hash missing' >&2; exit 1; }
            if [[ "$(uname -s)" != MINGW* ]]; then
              test "$(stat -c '%a' "$fixture/etc/joysong-demo/host-contract")" = 600 || { echo 'host contract mode' >&2; exit 1; }
              test "$(stat -c '%a' "$fixture/etc/joysong-demo/joysong.env")" = 640 || { echo 'environment mode' >&2; exit 1; }
              test "$(stat -c '%a' "$fixture/etc/mysql/joysong-uat-backup.cnf")" = 600 || { echo 'backup mode' >&2; exit 1; }
            fi
            test -f "$fixture/opt/joysong-actions-runner/.runner" || { echo 'runner marker missing' >&2; exit 1; }
            test "$(cat "$fixture/run/joysong-bootstrap-services/joysong-uat-runner.service.active")" = active || { echo 'runner not active' >&2; exit 1; }
            test ! -e "$fixture/run/joysong-bootstrap-services/joysong-demo.service.active" || { echo 'app unexpectedly active' >&2; exit 1; }
            before="$(find "$fixture" -type f ! -path '*/first.out' -print0 | sort -z | xargs -0 sha256sum | sha256sum)"
            "$bootstrap" apply "$source_dir" "$secrets" "$archive" 2.337.0 "$runner_sha" >"$fixture/second.out"
            grep -q 'no changes applied' "$fixture/second.out" || { echo 'idempotent message missing' >&2; exit 1; }
            after="$(find "$fixture" -type f ! -path '*/first.out' ! -path '*/second.out' -print0 | sort -z | xargs -0 sha256sum | sha256sum)"
            test "${before%% *}" = "${after%% *}" || { echo 'second apply mutated fixture' >&2; exit 1; }
            """
        )

    def test_runner_version_mismatch_is_rejected_before_registration(self):
        self.run_bash(
            self.fixture_preamble()
            + self.apply_fixture()
            + r"""
            sed -i 's/2.337.0/2.336.0/' "$payload/bin/Runner.Listener"
            tar -czf "$archive" -C "$payload" .
            runner_sha="$(sha256sum "$archive" | awk '{print $1}')"
            if "$bootstrap" apply "$source_dir" "$secrets" "$archive" 2.337.0 "$runner_sha" >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'runner archive version differs' "$fixture/out"
            test ! -e "$fixture/opt/joysong-actions-runner/.runner"
            """
        )

    def test_second_apply_fails_closed_on_template_secret_or_account_drift(self):
        self.run_bash(
            self.fixture_preamble()
            + self.apply_fixture()
            + r"""
            "$bootstrap" apply "$source_dir" "$secrets" "$archive" 2.337.0 "$runner_sha" >/dev/null
            printf '\n# drift\n' >>"$fixture/etc/systemd/system/joysong-demo.service"
            if "$bootstrap" apply "$source_dir" "$secrets" "$archive" 2.337.0 "$runner_sha" >"$fixture/out" 2>&1; then exit 1; fi
            grep -Eq 'host contract drifted|installed systemd unit drifted' "$fixture/out"
            cp "$source_dir/systemd/joysong-demo.service" "$fixture/etc/systemd/system/joysong-demo.service"
            sed -i 's/not-a-real-db-password-value/not-a-real-db-password-drift/' "$secrets/joysong.env"
            if "$bootstrap" apply "$source_dir" "$secrets" "$archive" 2.337.0 "$runner_sha" >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'installed application secret differs from input' "$fixture/out"
            sed -i 's/not-a-real-db-password-drift/not-a-real-db-password-value/' "$secrets/joysong.env"
            rm "$fixture/run/joysong-bootstrap-users/joysong-gh-runner"
            if "$bootstrap" apply "$source_dir" "$secrets" "$archive" 2.337.0 "$runner_sha" >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'joysong-gh-runner is missing' "$fixture/out"
            """
        )

    def test_second_apply_rejects_runner_registration_identity_drift(self):
        self.run_bash(
            self.fixture_preamble()
            + self.apply_fixture()
            + r"""
            "$bootstrap" apply "$source_dir" "$secrets" "$archive" 2.337.0 "$runner_sha" >/dev/null
            registration="$fixture/opt/joysong-actions-runner/.runner"
            cp "$registration" "$fixture/original-registration"
            for replacement in \
              's/joysong-uat-i-bp19abm7697mvhl0xewu/unexpected-runner/' \
              's,https://github.com/shimmail/joysong,https://github.com/unexpected/repository,' \
              's,/var/lib/joysong-actions-runner,/tmp/unexpected-work,' \
              's/"agentId":1/"agentId":0/' \
              's/"agentId":1/"agentId":true/' \
              's/"disableUpdate":true/"disableUpdate":false/' \
              's/"disableUpdate":true/"disableUpdate":1/' \
              's/"agentId":1/"agentId":1,"agentId":2/'; do
              sed "$replacement" "$fixture/original-registration" >"$registration"
              if "$bootstrap" apply "$source_dir" "$secrets" "$archive" 2.337.0 "$runner_sha" >"$fixture/out" 2>&1; then exit 1; fi
              grep -q 'runner registration identity drifted' "$fixture/out"
            done
            """
        )

    def test_forbidden_mysql_option_is_rejected_before_initialization(self):
        self.run_bash(
            self.fixture_preamble()
            + self.apply_fixture()
            + r"""
            printf 'unexpected\n' >"$secrets/extra"
            chmod 0600 "$secrets/extra"
            if "$bootstrap" apply "$source_dir" "$secrets" "$archive" 2.337.0 "$runner_sha" >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'unexpected or missing members' "$fixture/out"
            rm "$secrets/extra"
            printf 'ignore-table=myapp_worktree_uat.users\n' >>"$secrets/joysong-uat-backup.cnf"
            if "$bootstrap" apply "$source_dir" "$secrets" "$archive" 2.337.0 "$runner_sha" >"$fixture/out" 2>&1; then exit 1; fi
            grep -q 'forbidden keys' "$fixture/out"
            test ! -e "$fixture/etc/joysong-demo/host-contract"
            test ! -e "$fixture/opt/joysong-actions-runner"
            """
        )


if __name__ == "__main__":
    unittest.main()
