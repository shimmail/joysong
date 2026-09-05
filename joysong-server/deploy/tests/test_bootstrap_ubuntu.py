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
no_defaults=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --labels) shift; label="$1" ;;
    --no-default-labels) no_defaults=true ;;
    --token) shift ;;
  esac
  shift
done
[ "$label" = joysong-uat-deploy ]
[ "$no_defaults" = true ]
: >.runner
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
