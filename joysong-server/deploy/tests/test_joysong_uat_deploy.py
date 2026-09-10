from __future__ import print_function

import hashlib
import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path
from unittest import mock


DEPLOY_SCRIPT = (
    Path(__file__).resolve().parents[1] / "host" / "joysong-uat-deploy"
)
HOST_VERIFIER = (
    Path(__file__).resolve().parents[1] / "host" / "verify-uat-release.py"
)


def find_gnu_bash():
    candidates = []
    bash = shutil.which("bash")
    if bash:
        candidates.append(Path(bash))

    git = shutil.which("git")
    if git:
        git_root = Path(git).resolve().parent.parent
        candidates.extend(
            [
                git_root / "bin" / "bash.exe",
                git_root / "usr" / "bin" / "bash.exe",
            ]
        )

    seen = set()
    for candidate in candidates:
        candidate = str(candidate)
        if candidate in seen or not os.path.isfile(candidate):
            continue
        seen.add(candidate)
        try:
            result = subprocess.run(
                [candidate, "--version"],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                universal_newlines=True,
                timeout=10,
            )
        except (OSError, subprocess.SubprocessError):
            continue
        if result.returncode == 0 and "GNU bash" in result.stdout:
            return candidate
    return None


GNU_BASH = find_gnu_bash()


class JoySongUatDeployContractTest(unittest.TestCase):
    def test_admin_health_checks_use_the_uat_admin_subpath(self):
        source = DEPLOY_SCRIPT.read_text(encoding="utf-8")
        self.assertIn('"https://$ADMIN_PUBLIC_IP/admin/"', source)
        self.assertIn('"https://$ADMIN_PUBLIC_IP/admin/orders"', source)
        self.assertIn('"https://$ADMIN_PUBLIC_IP$asset_uri"', source)
        self.assertIn('--resolve "$ADMIN_PUBLIC_IP:443:127.0.0.1"', source)

    maxDiff = None

    def run_bash(self, body):
        if GNU_BASH is None:
            self.skipTest("GNU Bash is not available")

        preamble = r"""
            set -Eeuo pipefail
            if [[ "$EUID" -eq 0 ]]; then
              echo "host deploy fixture intentionally does not run as root" >&2
              exit 77
            fi
            fixture="$(mktemp -d)"
            cleanup_fixture() {
              command rm -rf -- "$fixture"
            }
            require_symlink_support() {
              mkdir -p "$fixture/.symlink-target"
              if ! ln -s "$fixture/.symlink-target" "$fixture/.symlink-probe" 2>/dev/null; then
                exit 77
              fi
              if [[ ! -L "$fixture/.symlink-probe" ]]; then
                exit 77
              fi
              command rm -f "$fixture/.symlink-probe"
            }
            install_atomic_link_mock() {
              atomic_link() {
                printf '%s\n' "$1" >"$2.target"
              }
            }
            mocked_link_target() {
              cat "$1.target"
            }
            export JOYSONG_UAT_TESTING=1
            export JOYSONG_UAT_TEST_ROOT="$fixture"
            source "$HOST_DEPLOY_SCRIPT"
            trap cleanup_fixture EXIT
        """
        script = textwrap.dedent(preamble) + "\n" + textwrap.dedent(body)
        environment = os.environ.copy()
        environment["HOST_DEPLOY_SCRIPT"] = DEPLOY_SCRIPT.as_posix()
        environment["TEST_PYTHON_BIN"] = Path(sys.executable).as_posix()
        # Git for Windows otherwise rewrites fixture-prefixed arguments that
        # resemble Unix paths before invoking mocked commands.
        environment["MSYS2_ARG_CONV_EXCL"] = "*"
        result = subprocess.run(
            [GNU_BASH, "-c", script],
            cwd=str(DEPLOY_SCRIPT.parents[3]),
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            encoding="utf-8",
            errors="replace",
            timeout=30,
        )
        if result.returncode == 77:
            self.skipTest(result.stderr.strip() or "fixture capability unavailable")
        return result

    def assert_bash_ok(self, body):
        result = self.run_bash(body)
        self.assertEqual(
            0,
            result.returncode,
            "stdout:\n{0}\nstderr:\n{1}".format(result.stdout, result.stderr),
        )

    def test_script_keeps_privileged_actions_behind_fixed_contracts(self):
        source = DEPLOY_SCRIPT.read_text(encoding="utf-8")
        for fragment in (
            "validate_arguments()",
            "safe_link_target()",
            "reject_reused_tag()",
            "verify_candidate()",
            "verify_capacity_and_backup_readiness()",
            "create_verified_backup()",
            "recover_on_failure()",
            "switch_release_pair()",
            "JOYSONG_UAT_TESTING",
            'readonly SERVICE=joysong-demo.service',
            'readonly PORT=8080',
            'readonly EXPECTED_DATABASE_NAME=myapp_worktree_uat',
            'if name != expected_name:',
        ):
            self.assertIn(fragment, source)
        self.assertLess(
            source.index('create_verified_backup "$tag" "$commit"'),
            source.index('switch_release_pair "$tag"'),
        )
        self.assertNotIn("eval ", source)
        self.assertNotIn("bash -c", source)

    def test_python_calls_are_isolated_from_runner_writable_modules(self):
        source = DEPLOY_SCRIPT.read_text(encoding="utf-8")
        python_calls = [
            line.strip()
            for line in source.splitlines()
            if '"$PYTHON_BIN"' in line and not line.lstrip().startswith("#")
        ]
        self.assertGreaterEqual(len(python_calls), 5)
        self.assertTrue(
            all('"$PYTHON_BIN" -I' in line for line in python_calls),
            "unisolated Python call found: {0}".format(python_calls),
        )
        self.assertIn("readonly PYTHON_BIN=/usr/bin/python3", source)

        self.assert_bash_ok(
            r"""
            python3() {
              if [[ "$#" -eq 3 && "$1" == -I && "$2" == - &&
                    -e "$3" ]] && command -v cygpath >/dev/null 2>&1; then
                "$TEST_PYTHON_BIN" "$1" "$2" "$(cygpath -w "$3")"
                return
              fi
              "$TEST_PYTHON_BIN" "$@"
            }
            runner_cwd="$fixture/runner-cwd"
            tree="$fixture/tree"
            mkdir -p "$runner_cwd" "$tree"
            printf payload >"$tree/data.txt"
            printf '%s\n' \
              'open("hashlib-loaded", "w").write("loaded")' \
              'raise RuntimeError("runner hashlib.py was imported")' \
              >"$runner_cwd/hashlib.py"
            printf '%s\n' \
              'open("csv-loaded", "w").write("loaded")' \
              'raise RuntimeError("runner csv.py was imported")' \
              >"$runner_cwd/csv.py"
            export PYTHONPATH="$runner_cwd"
            cd "$runner_cwd"

            tree_digest="$(tree_sha "$tree")"
            [[ "$tree_digest" =~ ^[0-9a-f]{64}$ ]]
            mysql_query() {
              printf '1\t33\tSQL_BASELINE\tB33__baseline.sql\t101\t1\n'
              printf '2\t34\tSQL\tV34__one.sql\t102\t1\n'
              printf '3\t35\tSQL\tV35__two.sql\t103\t1\n'
              printf '4\t36\tSQL\tV36__three.sql\t104\t1\n'
              printf '5\t37\tSQL\tV37__four.sql\t105\t1\n'
              printf '6\t38\tSQL\tV38__five.sql\t106\t1\n'
              printf '7\t39\tSQL\tV39__six.sql\t107\t1\n'
              printf '8\t40\tSQL\tV40__seven.sql\t108\t1\n'
            }
            flyway_digest="$(flyway_snapshot)"
            [[ "$flyway_digest" =~ ^[0-9a-f]{64}$ ]]
            [[ ! -e "$runner_cwd/hashlib-loaded" ]]
            [[ ! -e "$runner_cwd/csv-loaded" ]]
            """
        )

    def test_mysql_option_file_rejects_semantic_backup_overrides(self):
        self.assert_bash_ok(
            r"""
            python3() {
              if [[ "$#" -eq 4 && "$1" == -I && "$2" == - &&
                    -e "$3" ]] && command -v cygpath >/dev/null 2>&1; then
                "$TEST_PYTHON_BIN" "$1" "$2" "$(cygpath -w "$3")" "$4"
                return
              fi
              "$TEST_PYTHON_BIN" "$@"
            }
            mkdir -p "$(dirname "$MYSQL_CONFIG")"
            cat >"$MYSQL_CONFIG" <<'CNF'
            [client]
            host=127.0.0.1
            port=3306
            protocol=TCP
            user=joysong_uat_backup
            password=not-a-real-secret
            database=myapp_worktree_uat
            ignore-table=myapp_worktree_uat.users
            CNF
            if validate_mysql_option_file; then
              echo "forbidden MySQL option was accepted" >&2
              exit 1
            fi
            sed -i '/ignore-table/d' "$MYSQL_CONFIG"
            validate_mysql_option_file
            """
        )

    def test_previous_release_tree_identity_is_required_for_rollback(self):
        source = DEPLOY_SCRIPT.read_text(encoding="utf-8")
        for fragment in (
            'info.st_uid != 0 or stat.S_IMODE(info.st_mode) & 0o022',
            'CURRENT_BACKEND_TREE_SHA="$(immutable_tree_sha "$CURRENT_BACKEND")"',
            'CURRENT_ADMIN_TREE_SHA="$(immutable_tree_sha "$CURRENT_ADMIN")"',
            "OLD_BACKEND_TREE_SHA256=%s",
            "OLD_ADMIN_TREE_SHA256=%s",
            "previous_pair_unchanged; then",
        ):
            self.assertIn(fragment, source)
        self.assertLess(
            source.index('fail "current release pair content or permissions changed during backup"'),
            source.index("printf 'BACKUP_COMPLETE=true"),
        )

    @unittest.skipUnless(os.name == "posix", "openat race test requires POSIX")
    def test_bundle_freeze_stays_anchored_when_run_directory_is_replaced(self):
        spec = importlib.util.spec_from_file_location(
            "joysong_host_verifier", str(HOST_VERIFIER)
        )
        verifier = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(verifier)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            incoming = root / "incoming"
            run_directory = incoming / "123"
            moved_directory = incoming / "123-opened"
            outside_directory = root / "outside"
            state_directory = root / "state"
            incoming.mkdir(mode=0o700)
            run_directory.mkdir(mode=0o700)
            outside_directory.mkdir(mode=0o700)
            state_directory.mkdir(mode=0o700)
            bundle_name = "joysong-uat-v1.2.3-uat.1.tar.gz"
            original = b"anchored bundle"
            replacement = b"replacement bundle"
            (run_directory / bundle_name).write_bytes(original)
            (outside_directory / bundle_name).write_bytes(replacement)
            source_path = str(run_directory / bundle_name)
            destination = str(state_directory / "candidate.tar.gz")
            real_fstat = os.fstat
            raced = {"value": False}

            def racing_fstat(descriptor):
                details = real_fstat(descriptor)
                if not raced["value"]:
                    run_directory.rename(moved_directory)
                    run_directory.symlink_to(outside_directory, target_is_directory=True)
                    raced["value"] = True
                return details

            limits = {
                "MAX_BUNDLE_BYTES": 1024 * 1024,
                "MAX_OUTER_EXPANDED_BYTES": 1024 * 1024,
                "MAX_ADMIN_EXPANDED_BYTES": 1024 * 1024,
                "VERIFICATION_RESERVE_BYTES": 1024 * 1024,
            }
            try:
                with mock.patch.multiple(verifier, **limits), mock.patch.object(
                    verifier.os, "fstat", side_effect=racing_fstat
                ):
                    verifier.freeze_bundle(
                        source_path, destination, hashlib.sha256(original).hexdigest()
                    )
                self.assertTrue(raced["value"])
                self.assertEqual(original, Path(destination).read_bytes())
            finally:
                if run_directory.is_symlink():
                    run_directory.unlink()
                if moved_directory.exists():
                    moved_directory.rename(run_directory)

    def test_backend_health_requires_top_level_up_status(self):
        self.assert_bash_ok(
            r"""
            python3() {
              "$TEST_PYTHON_BIN" "$@"
            }
            health_body='{"status":"DOWN","components":{"db":{"status":"UP"}}}'
            run_with_timeout() {
              printf '%s' "$health_body"
            }
            if backend_health_up; then
              echo "component UP incorrectly overrode top-level DOWN" >&2
              exit 1
            fi

            health_body='{"status":"UP","components":{"db":{"status":"DOWN"}}}'
            backend_health_up
            """
        )

    def test_invalid_tag_commit_and_sha_are_rejected(self):
        self.assert_bash_ok(
            r"""
            commit="$(printf 'a%.0s' {1..40})"
            sha="$(printf 'b%.0s' {1..64})"
            reject_args() {
              if (trap - EXIT; validate_arguments "$@" >/dev/null 2>&1); then
                echo "unexpectedly accepted: $*" >&2
                return 1
              fi
            }
            reject_args deploy v1.2.3 "$commit" 1 "$sha"
            reject_args deploy v1.2.3-uat.1 ABC 1 "$sha"
            reject_args deploy v1.2.3-uat.1 "$(printf 'A%.0s' {1..40})" 1 "$sha"
            reject_args deploy v1.2.3-uat.1 "$commit" 1 NOT-A-SHA
            reject_args deploy v1.2.3-uat.1 "$commit" 1 "$(printf 'B%.0s' {1..64})"
            """
        )

    def test_repeated_tag_is_rejected(self):
        self.assert_bash_ok(
            r"""
            tag=v1.2.3-uat.7
            mkdir -p "$BACKEND_RELEASES/$tag"
            if (trap - EXIT; reject_reused_tag "$tag" >/dev/null 2>&1); then
              echo "existing release tag was accepted" >&2
              exit 1
            fi
            """
        )

    def test_current_symlink_cannot_escape_release_root(self):
        self.assert_bash_ok(
            r"""
            require_symlink_support
            mkdir -p "$BACKEND_RELEASES" "$fixture/outside-release"
            ln -s "$fixture/outside-release" "$BACKEND_CURRENT"
            if (trap - EXIT; safe_link_target "$BACKEND_CURRENT" "$BACKEND_RELEASES" >/dev/null 2>&1); then
              echo "out-of-bounds current link was accepted" >&2
              exit 1
            fi
            """
        )

    def test_candidate_migration_digest_must_match_current_release(self):
        self.assert_bash_ok(
            r"""
            mkdir -p "$STATE_ROOT"
            current="$(printf 'a%.0s' {1..64})"
            candidate="$(printf 'b%.0s' {1..64})"
            DEPLOY_MODE=existing
            CURRENT_MIGRATION_SHA="$current"
            validate_incoming_bundle() {
              printf '%s\n' "$fixture/candidate.tar.gz"
            }
            python3() {
              printf 'DATABASE_MIGRATIONS_SHA256=%s\n' "$candidate"
            }
            if (trap - EXIT; verify_candidate v1.2.3-uat.8 "$(printf 'c%.0s' {1..40})" 8 "$(printf 'd%.0s' {1..64})" >/dev/null 2>&1); then
              echo "migration-changing candidate was accepted" >&2
              exit 1
            fi
            """
        )

    def test_ubuntu_contract_uses_bootstrap_hashes_without_requiring_selinux(self):
        source = DEPLOY_SCRIPT.read_text(encoding="utf-8")
        self.assertIn(
            'NGINX_SITE="$(host_path /etc/nginx/conf.d/joysong-public.conf)"',
            source,
        )
        self.assertIn(
            'HOST_CONTRACT="$(host_path /etc/joysong-demo/host-contract)"',
            source,
        )
        self.assertIn(
            "readonly CONFIG_FILE MYSQL_CONFIG NGINX_SITE SYSTEMD_UNIT HOST_CONTRACT DEPLOY_ROOT",
            source,
        )
        self.assertIn('read_assignment "$HOST_CONTRACT" SYSTEMD_UNIT_SHA256', source)
        self.assertIn('read_assignment "$HOST_CONTRACT" NGINX_SITE_SHA256', source)
        self.assertIn("if command -v getenforce", source)
        self.assertNotIn("readonly SYSTEMD_UNIT_SHA256=", source)

    def test_empty_database_has_a_distinct_snapshot(self):
        self.assert_bash_ok(
            r"""
            mysql_query() { printf '0\n'; }
            flyway_snapshot() { touch "$fixture/flyway-called"; return 1; }
            [[ "$(database_state_snapshot)" == "$EMPTY_DATABASE_SHA256" ]]
            [[ ! -e "$fixture/flyway-called" ]]

            mysql_query() { printf '8\n'; }
            flyway_snapshot() { printf '%.0sa' {1..64}; printf '\n'; }
            [[ "$(database_state_snapshot)" == "$(printf '%.0sa' {1..64})" ]]
            """
        )

    def test_admin_secret_contract_blocks_state_loading_without_leaking_values(self):
        self.assert_bash_ok(
            r"""
            python3() {
              if command -v cygpath >/dev/null 2>&1; then
                "$TEST_PYTHON_BIN" "$1" "$2" "$(cygpath -w "$3")" "$4"
              else
                "$TEST_PYTHON_BIN" "$@"
              fi
            }
            mkdir -p "$(dirname "$CONFIG_FILE")"
            operations="$fixture/operations"
            : >"$operations"
            mode=fresh
            classify_deployment_mode() { DEPLOY_MODE="$mode"; }
            load_fresh_state() { printf 'fresh\n' >>"$operations"; }
            load_current_state() { printf 'existing\n' >>"$operations"; }
            write_config() {
              printf 'ADMIN_PHONE=13800000000\n' >"$CONFIG_FILE"
              if [[ "$1" != absent ]]; then
                printf 'ADMIN_PASSWORD=%s\n' "$1" >>"$CONFIG_FILE"
              fi
            }
            expect_rejection() {
              : >"$operations"
              if (load_deployment_state) >"$fixture/output" 2>&1; then
                echo 'unsafe administrator configuration was accepted' >&2
                exit 1
              fi
              [[ ! -s "$operations" ]]
              ! grep -Fq 'not-a-real-admin-password' "$fixture/output"
              ! grep -Fq '13800000000' "$fixture/output"
            }

            for password in absent '' short ' not-a-real-admin-password' 'not-a-real-admin-password '; do
              write_config "$password"
              expect_rejection
            done
            write_config not-a-real-admin-password
            printf 'ADMIN_PASSWORD=not-a-real-duplicate-password\n' >>"$CONFIG_FILE"
            expect_rejection
            write_config not-a-real-admin-password
            printf 'ADMIN_PHONE=invalid\n' >>"$CONFIG_FILE"
            expect_rejection
            write_config not-a-real-admin-password
            load_deployment_state
            [[ "$(cat "$operations")" == fresh ]]

            mode=existing
            for password in not-a-real-admin-password ''; do
              write_config "$password"
              expect_rejection
            done
            write_config absent
            load_deployment_state
            [[ "$(cat "$operations")" == existing ]]
            """
        )

    def test_fresh_preflight_is_read_only_and_reports_mode(self):
        self.assert_bash_ok(
            r"""
            operations="$fixture/operations"
            mkdir -p "$fixture/staging"
            : >"$operations"
            reject_reused_tag() { :; }
            validate_runtime_configuration() { :; }
            load_database_binding() { :; }
            load_deployment_state() {
              DEPLOY_MODE=fresh
              CURRENT_FLYWAY_SHA="$EMPTY_DATABASE_SHA256"
              CURRENT_MIGRATION_SHA=''
            }
            verify_candidate() {
              STAGING_ROOT="$fixture/staging"
              CANDIDATE_ROOT="$STAGING_ROOT/payload"
              CANDIDATE_MIGRATION_SHA="$(printf 'a%.0s' {1..64})"
            }
            verify_capacity_and_backup_readiness() { :; }
            run_with_timeout() {
              shift
              printf '%s\n' "$*" >>"$operations"
              return 0
            }
            output="$(preflight v1.2.3-uat.9 "$(printf 'b%.0s' {1..40})" 9 "$(printf 'c%.0s' {1..64})")"
            [[ "$output" == *'PREFLIGHT_OK=true'* ]]
            [[ "$output" == *'DEPLOY_MODE=fresh'* ]]
            [[ ! -s "$operations" ]]
            """
        )

    def test_fresh_state_gate_never_stops_or_starts_the_service(self):
        self.assert_bash_ok(
            r"""
            operations="$fixture/operations"
            : >"$operations"
            run_with_timeout() {
              shift
              printf '%s\n' "$*" >>"$operations"
              if [[ "$*" == 'systemctl is-active joysong-demo.service' ]]; then
                printf 'inactive\n'
              elif [[ "$*" == 'systemctl is-enabled joysong-demo.service' ]]; then
                printf 'disabled\n'
              fi
            }
            systemd_contract_sha() { printf '%.0sa' {1..64}; printf '\n'; }
            systemctl_property() {
              [[ "$1" == User ]] && printf '%s\n' "$SERVICE_USER" || printf '%s\n' "$SERVICE_GROUP"
            }
            database_state_snapshot() { printf '%s\n' "$EMPTY_DATABASE_SHA256"; }
            file_sha() { printf '%.0sb' {1..64}; printf '\n'; }
            nginx_config_sha() { printf '%.0sc' {1..64}; printf '\n'; }

            load_fresh_state
            [[ "$CURRENT_FLYWAY_SHA" == "$EMPTY_DATABASE_SHA256" ]]
            ! grep -Eq 'systemctl (stop|start|disable|enable)' "$operations"
            """
        )

    def test_fresh_deploy_backs_up_before_install_and_start(self):
        self.assert_bash_ok(
            r"""
            operations="$fixture/operations"
            STAGING_ROOT="$fixture/staging"
            mkdir -p "$STAGING_ROOT"
            : >"$operations"
            reject_reused_tag() { :; }
            validate_runtime_configuration() { :; }
            load_database_binding() { :; }
            load_deployment_state() {
              DEPLOY_MODE=fresh
              CURRENT_FLYWAY_SHA="$EMPTY_DATABASE_SHA256"
            }
            verify_candidate() { printf 'verify\n' >>"$operations"; }
            verify_capacity_and_backup_readiness() { printf 'capacity\n' >>"$operations"; }
            create_verified_backup() { printf 'backup\n' >>"$operations"; }
            database_state_snapshot() { printf '%s\n' "$CURRENT_FLYWAY_SHA"; }
            install_candidate() { printf 'install\n' >>"$operations"; }
            switch_fresh_release_pair() { printf 'start\n' >>"$operations"; }

            deploy v1.2.3-uat.10 "$(printf 'b%.0s' {1..40})" 10 "$(printf 'c%.0s' {1..64})" >/dev/null
            [[ "$(tr '\n' ' ' <"$operations")" == 'verify capacity backup install start ' ]]
            """
        )

    def test_fresh_switch_commits_current_pair_without_previous_pair(self):
        self.assert_bash_ok(
            r"""
            install_atomic_link_mock
            tag=v1.2.3-uat.10
            operations="$fixture/operations"
            mkdir -p "$STATE_ROOT" "$BACKEND_RELEASES/$tag" "$ADMIN_RELEASES/$tag"
            : >"$operations"
            DEPLOY_MODE=fresh
            DEPLOY_TAG="$tag"
            DEPLOY_COMMIT="$(printf 'b%.0s' {1..40})"
            DEPLOY_RUN_ID=10
            DEPLOY_BUNDLE_SHA="$(printf 'c%.0s' {1..64})"
            CURRENT_FLYWAY_SHA="$EMPTY_DATABASE_SHA256"
            CURRENT_CONFIG_SHA="$(printf 'd%.0s' {1..64})"
            CURRENT_NGINX_SHA="$(printf 'e%.0s' {1..64})"
            CURRENT_SYSTEMD_SHA="$(printf 'f%.0s' {1..64})"
            CANDIDATE_MIGRATION_SHA="$(printf '1%.0s' {1..64})"
            BACKUP_PATH="$BACKUP_ROOT/initial"
            verify_fresh_state_unchanged() { :; }
            run_with_timeout() { shift; printf '%s\n' "$*" >>"$operations"; }
            wait_for_backend() { return 0; }
            flyway_snapshot() { printf '%.0sa' {1..64}; printf '\n'; }
            verify_admin_locally() { return 0; }
            verify_service_contract() { return 0; }
            backend_health_up() { return 0; }
            file_sha() { printf '%s\n' "$CURRENT_CONFIG_SHA"; }
            nginx_config_sha() { printf '%s\n' "$CURRENT_NGINX_SHA"; }
            systemd_contract_sha() { printf '%s\n' "$CURRENT_SYSTEMD_SHA"; }
            sleep() { :; }
            write_state() { printf '%s\n' "$2" >"$fixture/release-state"; }

            switch_fresh_release_pair "$tag" >/dev/null
            [[ "$(mocked_link_target "$BACKEND_CURRENT")" == "$BACKEND_RELEASES/$tag" ]]
            [[ "$(mocked_link_target "$ADMIN_CURRENT")" == "$ADMIN_RELEASES/$tag" ]]
            [[ ! -e "$BACKEND_PREVIOUS.target" && ! -e "$ADMIN_PREVIOUS.target" ]]
            [[ ! -e "$TRANSACTION_MARKER" ]]
            [[ "$(cat "$fixture/release-state")" == deployed ]]
            grep -q 'systemctl start joysong-demo.service' "$operations"
            grep -q 'systemctl enable joysong-demo.service' "$operations"
            """
        )

    def test_fresh_failure_with_unchanged_empty_database_is_retryable(self):
        self.assert_bash_ok(
            r"""
            operations="$fixture/operations"
            mkdir -p "$STATE_ROOT"
            : >"$TRANSACTION_MARKER"
            : >"$operations"
            DEPLOY_MODE=fresh
            DEPLOY_TAG=v1.2.3-uat.11
            CURRENT_FLYWAY_SHA="$EMPTY_DATABASE_SHA256"
            CURRENT_PID=101
            TRANSACTION_ACTIVE=true
            TRANSACTION_COMMITTED=false
            OUTAGE_STARTED="$SECONDS"
            run_with_timeout() {
              shift
              printf '%s\n' "$*" >>"$operations"
              return 0
            }
            service_stopped_and_port_free() { return 0; }
            database_state_snapshot() { printf '%s\n' "$CURRENT_FLYWAY_SHA"; }
            clear_fresh_candidate_links() { printf 'clear-links\n' >>"$operations"; }
            write_state() { printf '%s\n' "$2" >"$fixture/recovery-state"; }

            set +e
            (trap - EXIT; set +e; false; recover_on_failure)
            status=$?
            set -e
            [[ "$status" -ne 0 ]]
            [[ ! -e "$TRANSACTION_MARKER" ]]
            [[ "$(cat "$fixture/recovery-state")" == failed-retryable ]]
            grep -q '^clear-links$' "$operations"
            grep -q 'systemctl disable joysong-demo.service' "$operations"
            """
        )

    def test_fresh_failure_after_database_change_stays_disabled_with_marker(self):
        self.assert_bash_ok(
            r"""
            operations="$fixture/operations"
            mkdir -p "$STATE_ROOT"
            : >"$TRANSACTION_MARKER"
            : >"$operations"
            DEPLOY_MODE=fresh
            DEPLOY_TAG=v1.2.3-uat.12
            CURRENT_FLYWAY_SHA="$EMPTY_DATABASE_SHA256"
            CURRENT_PID=101
            TRANSACTION_ACTIVE=true
            TRANSACTION_COMMITTED=false
            OUTAGE_STARTED="$SECONDS"
            run_with_timeout() {
              shift
              printf '%s\n' "$*" >>"$operations"
              return 0
            }
            service_stopped_and_port_free() { return 0; }
            database_state_snapshot() { printf '%.0sa' {1..64}; printf '\n'; }
            clear_fresh_candidate_links() { printf 'unsafe-clear\n' >>"$operations"; }

            set +e
            (trap - EXIT; set +e; false; recover_on_failure)
            status=$?
            set -e
            [[ "$status" -ne 0 ]]
            [[ -e "$TRANSACTION_MARKER" ]]
            ! grep -q '^unsafe-clear$' "$operations"
            grep -q 'systemctl disable joysong-demo.service' "$operations"
            grep -q 'systemctl stop joysong-demo.service' "$operations"
            """
        )

    def test_successful_fresh_links_are_classified_as_existing_next_time(self):
        self.assert_bash_ok(
            r"""
            links=none
            path_present() {
              [[ "$links" == current &&
                 ("$1" == "$BACKEND_CURRENT" || "$1" == "$ADMIN_CURRENT") ]]
            }
            classify_deployment_mode
            [[ "$DEPLOY_MODE" == fresh ]]
            links=current
            classify_deployment_mode
            [[ "$DEPLOY_MODE" == existing ]]
            """
        )

    def test_backup_failure_never_reaches_service_switch(self):
        self.assert_bash_ok(
            r"""
            operations="$fixture/operations"
            : >"$operations"
            reject_reused_tag() { :; }
            validate_runtime_configuration() { :; }
            load_database_binding() { :; }
            load_deployment_state() { DEPLOY_MODE=existing; }
            verify_candidate() { :; }
            verify_capacity_and_backup_readiness() { :; }
            install_candidate() { :; }
            create_verified_backup() { return 1; }
            flyway_snapshot() { printf '%s\n' "$CURRENT_FLYWAY_SHA"; }
            switch_release_pair() { printf 'switch\n' >>"$operations"; }
            run_with_timeout() {
              shift
              printf '%s\n' "$*" >>"$operations"
              return 0
            }
            CURRENT_FLYWAY_SHA="$(printf 'a%.0s' {1..64})"

            set +e
            (trap - EXIT; set -e; deploy v1.2.3-uat.9 "$(printf 'b%.0s' {1..40})" 9 "$(printf 'c%.0s' {1..64})")
            status=$?
            set -e
            [[ "$status" -ne 0 ]]
            [[ ! -s "$operations" ]]
            """
        )

    def test_listener_identity_accepts_only_exact_loopback_and_exclusive_mainpid(self):
        self.assert_bash_ok(
            r"""
            python3() { "$TEST_PYTHON_BIN" "$@"; }
            run_with_timeout() { shift; "$@"; }
            ss_status=0
            ss() {
              [[ "$*" == '-H -ltnp sport = :8080' ]] || return 2
              cat "$fixture/listener"
              return "$ss_status"
            }
            for address in 127.0.0.1:8080 '[::ffff:127.0.0.1]:8080' '[::ffff:7f00:1]:8080'; do
              printf 'LISTEN 0 100 %s *:* users:(("java",pid=123,fd=10))\n' "$address" >"$fixture/listener"
              listener_owned_by_pid 123 || { printf 'valid listener rejected: %s\n' "$address" >&2; exit 1; }
            done
            for line in \
              'LISTEN 0 100 127.0.0.1:8081 *:* users:(("java",pid=123,fd=10))' \
              'LISTEN 0 100 127.0.0.1:80800 *:* users:(("java",pid=123,fd=10))' \
              'LISTEN 0 100 0.0.0.0:8080 *:* users:(("java",pid=123,fd=10))' \
              'LISTEN 0 100 [::]:8080 *:* users:(("java",pid=123,fd=10))' \
              'LISTEN 0 100 *:8080 *:* users:(("java",pid=123,fd=10))' \
              'LISTEN 0 100 [::1]:8080 *:* users:(("java",pid=123,fd=10))' \
              'LISTEN 0 100 127.0.0.2:8080 *:* users:(("java",pid=123,fd=10))' \
              'LISTEN 0 100 [::ffff:127.0.0.2]:8080 *:* users:(("java",pid=123,fd=10))' \
              'LISTEN 0 100 127.0.0.1:8080 *:* users:(("java",pid=124,fd=10))' \
              'LISTEN 0 100 127.0.0.1:8080 *:* users:(("java",pid=123,fd=10),("other",pid=124,fd=11))' \
              'LISTEN 0 100 127.0.0.1:8080 *:*' \
              'LISTEN 0 100 0.0.0.0:8080 127.0.0.1:8080 users:(("java",pid=123,fd=10))' \
              'LISTEN 0 100 10.0.0.1:8080 *:* users:(("127.0.0.1:8080",pid=123,fd=10))' \
              'ESTAB 0 100 127.0.0.1:8080 *:* users:(("java",pid=123,fd=10))'; do
              printf '%s\n' "$line" >"$fixture/listener"
              if listener_owned_by_pid 123; then printf 'unsafe listener accepted: %s\n' "$line" >&2; exit 1; fi
            done
            printf 'LISTEN 0 100 127.0.0.1:8080 *:* users:(("java",pid=123,fd=10))\n' >"$fixture/single"
            cat "$fixture/single" "$fixture/single" >"$fixture/listener"
            if listener_owned_by_pid 123; then echo 'multiple listeners accepted' >&2; exit 1; fi
            : >"$fixture/listener"
            if listener_owned_by_pid 123; then echo 'missing listener accepted' >&2; exit 1; fi
            cp "$fixture/single" "$fixture/listener"
            ss_status=1
            if listener_owned_by_pid 123; then echo 'failed ss inspection accepted' >&2; exit 1; fi
            """
        )

    def test_dump_filters_mysql_only_database_without_secret_leaks_and_cleans_defaults(self):
        self.assert_bash_ok(
            r"""
            if [[ "$(uname -s)" == MINGW* ]]; then
              # NTFS does not implement install's Unix ownership/mode handling.
              install() {
                if [[ "$1" == -d && "$2" == -m ]]; then
                  mkdir -p "${@:4}"
                else
                  [[ "$1" == -m && "$#" == 4 ]]
                  cp "$3" "$4"
                fi
              }
            fi
            python3() {
              local argument
              local -a converted=()
              for argument in "$@"; do
                if [[ "$argument" == /* && -e "$argument" ]] && command -v cygpath >/dev/null 2>&1; then
                  converted+=("$(cygpath -w "$argument")")
                else
                  converted+=("$argument")
                fi
              done
              "$TEST_PYTHON_BIN" "${converted[@]}"
            }
            mkdir -p "$(dirname "$CONFIG_FILE")" "$(dirname "$MYSQL_CONFIG")" "$BACKUP_ROOT" "$STATE_ROOT" "$UPLOADS" "$PRIVATE_DATA" "$STAGING_DATA"
            printf 'JWT_SECRET=fake-runtime-configuration\n' >"$CONFIG_FILE"
            cat >"$MYSQL_CONFIG" <<'CNF'
            [client]
            host=127.0.0.1
            port=3306
            protocol=TCP
            user=joysong_uat_backup
            password="not-a-real-secret # equals=colon:backslash\\value"
            database=myapp_worktree_uat
            CNF
            original_cnf_sha="$(file_sha "$MYSQL_CONFIG")"
            validate_mysql_option_file
            DATABASE_HOST=127.0.0.1
            DATABASE_PORT=3306
            DATABASE_NAME=myapp_worktree_uat
            DEPLOY_MODE=fresh
            CURRENT_CONFIG_SHA="$(file_sha "$CONFIG_FILE")"
            CURRENT_FLYWAY_SHA="$EMPTY_DATABASE_SHA256"
            CURRENT_NGINX_SHA="$(printf 'a%.0s' {1..64})"
            CURRENT_SYSTEMD_SHA="$(printf 'b%.0s' {1..64})"
            CANDIDATE_MIGRATION_SHA="$(printf 'c%.0s' {1..64})"
            database_state_snapshot() { printf '%s\n' "$EMPTY_DATABASE_SHA256"; }
            service_stopped_and_port_free() { return 0; }
            run_with_timeout() { shift; "$@"; }
            # Exercise real archive/hash handling with portable tar options.
            tar() {
              if [[ "$1" == --create ]]; then
                command tar -czf "$BACKUP_PATH/persistent-data.tar.gz" -C "$DATA_ROOT" uploads private upload-staging
              else
                command tar -dzf "$BACKUP_PATH/persistent-data.tar.gz" -C "$DATA_ROOT"
              fi
            }
            mysqldump() {
              local defaults="${1#--defaults-file=}"
              if grep -Eq '^[[:space:]]*database[[:space:]]*[:=]' "$defaults"; then
                printf "mysqldump: [ERROR] unknown variable 'database=myapp_worktree_uat'\n" >&2
                return 2
              fi
              [[ "$1" == --defaults-file=* && "$defaults" == "$STAGING_ROOT/"* && "$defaults" != "$MYSQL_CONFIG" ]] || return 10
              [[ -f "$defaults" && ! -L "$defaults" && "${!#}" == "$DATABASE_NAME" ]] || return 11
              cmp <(sed '/^[[:space:]]*database[[:space:]]*[:=]/d' "$MYSQL_CONFIG") "$defaults" || return 12
              [[ "$*" != *not-a-real-secret* && "$*" != *--password* && -z "${MYSQL_PWD:-}" ]] || return 13
              if env | grep -qF not-a-real-secret; then return 3; fi
              if [[ "$(uname -s)" != MINGW* ]]; then
                [[ "$(stat -c '%a' "$defaults")" == 600 && "$(stat -c '%a' "$STAGING_ROOT")" == 700 ]] || return 14
              fi
              printf '%s\n' "$defaults" >"$fixture/used-defaults"
              if [[ "$dump_failure" == true ]]; then return 4; fi
              printf -- '-- fixture dump without database selectors\n'
            }
            unset MYSQL_PWD
            if mysqldump --defaults-file="$MYSQL_CONFIG" "$DATABASE_NAME" >"$fixture/original.out" 2>&1; then exit 1; fi
            grep -q 'unknown variable' "$fixture/original.out"
            for dump_failure in false true; do
              STAGING_ROOT="$STATE_ROOT/dump-stage-$dump_failure"
              mkdir -m 0700 "$STAGING_ROOT"
              if [[ "$dump_failure" == false ]]; then
                create_verified_backup v1.2.3-uat.21 "$(printf 'd%.0s' {1..40})"
                [[ -f "$BACKUP_PATH/BACKUP_COMPLETE" ]]
                (cd "$BACKUP_PATH" && sha256sum --check --status SHA256SUMS)
                [[ ! -e "$(cat "$fixture/used-defaults")" ]]
              else
                set +e
                (trap recover_on_failure EXIT; set -e; create_verified_backup v1.2.3-uat.22 "$(printf 'd%.0s' {1..40})") >"$fixture/failure.out" 2>&1
                status=$?
                set -e
                [[ "$status" -ne 0 && ! -e "$STAGING_ROOT" && ! -e "$(cat "$fixture/used-defaults")" ]]
                grep -q 'database backup failed' "$fixture/failure.out"
                if grep -qF not-a-real-secret "$fixture/failure.out"; then exit 1; fi
                [[ -z "$(find "$BACKUP_ROOT" -path '*v1.2.3-uat.22/BACKUP_COMPLETE' -print -quit)" ]]
              fi
              [[ "$(file_sha "$MYSQL_CONFIG")" == "$original_cnf_sha" ]]
            done
            """
        )

    def test_insufficient_capacity_never_installs_or_stops(self):
        self.assert_bash_ok(
            r"""
            operations="$fixture/operations"
            : >"$operations"
            reject_reused_tag() { :; }
            validate_runtime_configuration() { :; }
            load_database_binding() { :; }
            load_deployment_state() { DEPLOY_MODE=existing; }
            verify_candidate() { :; }
            verify_capacity_and_backup_readiness() { return 1; }
            install_candidate() { printf 'install\n' >>"$operations"; }
            create_verified_backup() { printf 'backup\n' >>"$operations"; }
            switch_release_pair() { printf 'switch\n' >>"$operations"; }
            run_with_timeout() {
              shift
              printf '%s\n' "$*" >>"$operations"
              return 0
            }

            set +e
            (trap - EXIT; set -e; deploy v1.2.3-uat.10 "$(printf 'b%.0s' {1..40})" 10 "$(printf 'c%.0s' {1..64})")
            status=$?
            set -e
            [[ "$status" -ne 0 ]]
            [[ ! -s "$operations" ]]
            """
        )

    def test_backend_startup_timeout_stops_before_admin_switch(self):
        self.assert_bash_ok(
            r"""
            install_atomic_link_mock
            tag=v1.2.3-uat.11
            operations="$fixture/operations"
            mkdir -p "$STATE_ROOT" "$BACKEND_RELEASES/$tag" "$ADMIN_RELEASES/$tag"
            : >"$operations"
            CURRENT_BACKEND="$BACKEND_RELEASES/old"
            CURRENT_ADMIN="$ADMIN_RELEASES/old"
            CURRENT_PID=101
            write_transaction_marker() { : >"$TRANSACTION_MARKER"; }
            run_with_timeout() {
              shift
              printf '%s\n' "$*" >>"$operations"
              return 0
            }
            service_stopped_and_port_free() { return 0; }
            wait_for_backend() { return 1; }

            set +e
            (trap - EXIT; set -e; switch_release_pair "$tag")
            status=$?
            set -e
            [[ "$status" -ne 0 ]]
            [[ -e "$TRANSACTION_MARKER" ]]
            [[ "$(mocked_link_target "$BACKEND_CURRENT")" == "$BACKEND_RELEASES/$tag" ]]
            [[ ! -e "$ADMIN_CURRENT.target" ]]
            grep -q 'systemctl start joysong-demo.service' "$operations"
            """
        )

    def test_admin_failure_occurs_after_both_candidate_links_are_selected(self):
        self.assert_bash_ok(
            r"""
            install_atomic_link_mock
            tag=v1.2.3-uat.12
            old_backend="$BACKEND_RELEASES/old"
            old_admin="$ADMIN_RELEASES/old"
            mkdir -p "$STATE_ROOT" "$old_backend" "$old_admin" "$BACKEND_RELEASES/$tag" "$ADMIN_RELEASES/$tag"
            atomic_link "$old_backend" "$BACKEND_CURRENT"
            atomic_link "$old_admin" "$ADMIN_CURRENT"
            CURRENT_BACKEND="$old_backend"
            CURRENT_ADMIN="$old_admin"
            CURRENT_PID=101
            CURRENT_FLYWAY_SHA="$(printf 'a%.0s' {1..64})"
            write_transaction_marker() { : >"$TRANSACTION_MARKER"; }
            run_with_timeout() { return 0; }
            service_stopped_and_port_free() { return 0; }
            wait_for_backend() { return 0; }
            flyway_snapshot() { printf '%s\n' "$CURRENT_FLYWAY_SHA"; }
            verify_admin_locally() { return 1; }

            set +e
            (trap - EXIT; set -e; switch_release_pair "$tag")
            status=$?
            set -e
            [[ "$status" -ne 0 ]]
            [[ -e "$TRANSACTION_MARKER" ]]
            [[ "$(mocked_link_target "$BACKEND_CURRENT")" == "$BACKEND_RELEASES/$tag" ]]
            [[ "$(mocked_link_target "$ADMIN_CURRENT")" == "$ADMIN_RELEASES/$tag" ]]
            """
        )

    def test_unchanged_history_rolls_back_both_current_links(self):
        self.assert_bash_ok(
            r"""
            install_atomic_link_mock
            tag=v1.2.3-uat.13
            old_backend="$BACKEND_RELEASES/old"
            old_admin="$ADMIN_RELEASES/old"
            new_backend="$BACKEND_RELEASES/$tag"
            new_admin="$ADMIN_RELEASES/$tag"
            operations="$fixture/operations"
            mkdir -p "$STATE_ROOT" "$old_backend" "$old_admin" "$new_backend" "$new_admin"
            atomic_link "$new_backend" "$BACKEND_CURRENT"
            atomic_link "$new_admin" "$ADMIN_CURRENT"
            : >"$TRANSACTION_MARKER"
            : >"$operations"
            CURRENT_BACKEND="$old_backend"
            CURRENT_ADMIN="$old_admin"
            CURRENT_PID=101
            CURRENT_FLYWAY_SHA="$(printf 'a%.0s' {1..64})"
            CURRENT_BACKEND_TREE_SHA="$(printf 'b%.0s' {1..64})"
            CURRENT_ADMIN_TREE_SHA="$(printf 'c%.0s' {1..64})"
            TRANSACTION_ACTIVE=true
            TRANSACTION_COMMITTED=false
            PREVIOUS_BINDING_STARTED=false
            DEPLOY_TAG="$tag"
            OUTAGE_STARTED="$SECONDS"
            run_with_timeout() {
              shift
              printf '%s\n' "$*" >>"$operations"
              return 0
            }
            flyway_snapshot() { printf '%s\n' "$CURRENT_FLYWAY_SHA"; }
            immutable_tree_sha() {
              if [[ "$1" == "$CURRENT_BACKEND" ]]; then
                printf '%s\n' "$CURRENT_BACKEND_TREE_SHA"
              else
                printf '%s\n' "$CURRENT_ADMIN_TREE_SHA"
              fi
            }
            service_stopped_and_port_free() { return 0; }
            wait_for_backend() { return 0; }
            verify_admin_locally() { return 0; }
            write_state() { printf '%s\n' "$2" >"$fixture/recovery-state"; }

            set +e
            (trap - EXIT; set +e; false; recover_on_failure)
            status=$?
            set -e
            [[ "$status" -ne 0 ]]
            [[ "$(mocked_link_target "$BACKEND_CURRENT")" == "$old_backend" ]]
            [[ "$(mocked_link_target "$ADMIN_CURRENT")" == "$old_admin" ]]
            [[ ! -e "$TRANSACTION_MARKER" ]]
            [[ "$(cat "$fixture/recovery-state")" == rolled-back ]]
            grep -q 'systemctl start joysong-demo.service' "$operations"
            grep -q 'systemctl enable joysong-demo.service' "$operations"
            """
        )

    def test_changed_previous_release_tree_is_never_reconnected(self):
        self.assert_bash_ok(
            r"""
            install_atomic_link_mock
            tag=v1.2.3-uat.14
            old_backend="$BACKEND_RELEASES/old"
            old_admin="$ADMIN_RELEASES/old"
            new_backend="$BACKEND_RELEASES/$tag"
            new_admin="$ADMIN_RELEASES/$tag"
            operations="$fixture/operations"
            mkdir -p "$STATE_ROOT" "$old_backend" "$old_admin" "$new_backend" "$new_admin"
            atomic_link "$new_backend" "$BACKEND_CURRENT"
            atomic_link "$new_admin" "$ADMIN_CURRENT"
            : >"$TRANSACTION_MARKER"
            : >"$operations"
            CURRENT_BACKEND="$old_backend"
            CURRENT_ADMIN="$old_admin"
            CURRENT_PID=101
            CURRENT_FLYWAY_SHA="$(printf 'a%.0s' {1..64})"
            CURRENT_BACKEND_TREE_SHA="$(printf 'b%.0s' {1..64})"
            CURRENT_ADMIN_TREE_SHA="$(printf 'c%.0s' {1..64})"
            TRANSACTION_ACTIVE=true
            TRANSACTION_COMMITTED=false
            PREVIOUS_BINDING_STARTED=false
            DEPLOY_TAG="$tag"
            OUTAGE_STARTED="$SECONDS"
            run_with_timeout() {
              shift
              printf '%s\n' "$*" >>"$operations"
              return 0
            }
            flyway_snapshot() { printf '%s\n' "$CURRENT_FLYWAY_SHA"; }
            immutable_tree_sha() {
              if [[ "$1" == "$CURRENT_BACKEND" ]]; then
                printf '%.0s0' {1..64}; printf '\n'
              else
                printf '%s\n' "$CURRENT_ADMIN_TREE_SHA"
              fi
            }
            service_stopped_and_port_free() { return 0; }

            set +e
            (trap - EXIT; set +e; false; recover_on_failure)
            status=$?
            set -e
            [[ "$status" -ne 0 ]]
            [[ "$(mocked_link_target "$BACKEND_CURRENT")" == "$new_backend" ]]
            [[ "$(mocked_link_target "$ADMIN_CURRENT")" == "$new_admin" ]]
            [[ -e "$TRANSACTION_MARKER" ]]
            ! grep -q 'systemctl start joysong-demo.service' "$operations"
            """
        )

    def test_changed_history_keeps_candidate_pair_stopped_and_marker(self):
        self.assert_bash_ok(
            r"""
            install_atomic_link_mock
            tag=v1.2.3-uat.14
            old_backend="$BACKEND_RELEASES/old"
            old_admin="$ADMIN_RELEASES/old"
            new_backend="$BACKEND_RELEASES/$tag"
            new_admin="$ADMIN_RELEASES/$tag"
            operations="$fixture/operations"
            mkdir -p "$STATE_ROOT" "$old_backend" "$old_admin" "$new_backend" "$new_admin"
            atomic_link "$new_backend" "$BACKEND_CURRENT"
            atomic_link "$new_admin" "$ADMIN_CURRENT"
            : >"$TRANSACTION_MARKER"
            : >"$operations"
            CURRENT_BACKEND="$old_backend"
            CURRENT_ADMIN="$old_admin"
            CURRENT_PID=101
            CURRENT_FLYWAY_SHA="$(printf 'a%.0s' {1..64})"
            TRANSACTION_ACTIVE=true
            TRANSACTION_COMMITTED=false
            PREVIOUS_BINDING_STARTED=false
            DEPLOY_TAG="$tag"
            OUTAGE_STARTED="$SECONDS"
            run_with_timeout() {
              shift
              printf '%s\n' "$*" >>"$operations"
              return 0
            }
            flyway_snapshot() { printf '%.0s0' {1..64}; printf '\n'; }
            service_stopped_and_port_free() { return 0; }

            set +e
            (trap - EXIT; set +e; false; recover_on_failure)
            status=$?
            set -e
            [[ "$status" -ne 0 ]]
            [[ "$(mocked_link_target "$BACKEND_CURRENT")" == "$new_backend" ]]
            [[ "$(mocked_link_target "$ADMIN_CURRENT")" == "$new_admin" ]]
            [[ -e "$TRANSACTION_MARKER" ]]
            grep -q 'systemctl disable joysong-demo.service' "$operations"
            grep -q 'systemctl stop joysong-demo.service' "$operations"
            ! grep -q 'systemctl start joysong-demo.service' "$operations"
            """
        )

    def test_marker_cleanup_failure_keeps_healthy_new_pair_and_previous_pair(self):
        self.assert_bash_ok(
            r"""
            install_atomic_link_mock
            tag=v1.2.3-uat.15
            old_backend="$BACKEND_RELEASES/old"
            old_admin="$ADMIN_RELEASES/old"
            new_backend="$BACKEND_RELEASES/$tag"
            new_admin="$ADMIN_RELEASES/$tag"
            mkdir -p "$STATE_ROOT" "$old_backend" "$old_admin" "$new_backend" "$new_admin"
            printf old-jar >"$old_backend/joysong-server.jar"
            printf old-admin >"$old_admin/index.html"
            printf new-jar >"$new_backend/joysong-server.jar"
            printf new-admin >"$new_admin/index.html"
            atomic_link "$old_backend" "$BACKEND_CURRENT"
            atomic_link "$old_admin" "$ADMIN_CURRENT"
            CURRENT_BACKEND="$old_backend"
            CURRENT_ADMIN="$old_admin"
            CURRENT_JAR="$old_backend/joysong-server.jar"
            CURRENT_PID=101
            CURRENT_FLYWAY_SHA="$(printf 'a%.0s' {1..64})"
            CURRENT_MIGRATION_SHA="$(printf 'b%.0s' {1..64})"
            CURRENT_CONFIG_SHA="$(printf 'c%.0s' {1..64})"
            CURRENT_NGINX_SHA="$(printf 'd%.0s' {1..64})"
            CURRENT_SYSTEMD_SHA="$(printf '1%.0s' {1..64})"
            DEPLOY_TAG="$tag"
            DEPLOY_COMMIT="$(printf 'e%.0s' {1..40})"
            DEPLOY_RUN_ID=15
            DEPLOY_BUNDLE_SHA="$(printf 'f%.0s' {1..64})"
            BACKUP_PATH="$BACKUP_ROOT/verified"
            write_transaction_marker() { : >"$TRANSACTION_MARKER"; }
            write_state() { :; }
            run_with_timeout() { return 0; }
            service_stopped_and_port_free() { return 0; }
            wait_for_backend() { return 0; }
            flyway_snapshot() { printf '%s\n' "$CURRENT_FLYWAY_SHA"; }
            verify_admin_locally() { return 0; }
            verify_service_contract() { return 0; }
            backend_health_up() { return 0; }
            file_sha() { printf '%s\n' "$CURRENT_CONFIG_SHA"; }
            nginx_config_sha() { printf '%s\n' "$CURRENT_NGINX_SHA"; }
            systemd_contract_sha() { printf '%s\n' "$CURRENT_SYSTEMD_SHA"; }
            tree_sha() { printf '%s\n' "$CURRENT_MIGRATION_SHA"; }
            sleep() { :; }
            rm() {
              local argument
              for argument in "$@"; do
                if [[ "$argument" == "$TRANSACTION_MARKER" ]]; then
                  return 1
                fi
              done
              command rm "$@"
            }

            set +e
            (trap recover_on_failure EXIT; set -e; switch_release_pair "$tag")
            status=$?
            set -e
            [[ "$status" -ne 0 ]]
            [[ -e "$TRANSACTION_MARKER" ]]
            [[ "$(mocked_link_target "$BACKEND_CURRENT")" == "$new_backend" ]]
            [[ "$(mocked_link_target "$ADMIN_CURRENT")" == "$new_admin" ]]
            [[ "$(mocked_link_target "$BACKEND_PREVIOUS")" == "$old_backend" ]]
            [[ "$(mocked_link_target "$ADMIN_PREVIOUS")" == "$old_admin" ]]
            """
        )

    def test_candidate_and_recovery_deadlines_share_one_hard_outage_budget(self):
        self.assert_bash_ok(
            r"""
            [[ "$OUTAGE_LIMIT_SECONDS" -eq 180 ]]
            [[ "$RECOVERY_RESERVE_SECONDS" -eq 90 ]]

            executed="$fixture/should-not-run"
            SECONDS=10
            ACTIVE_DEADLINE=9
            set +e
            run_with_timeout 5 touch "$executed"
            timeout_status=$?
            set -e
            [[ "$timeout_status" -eq 124 ]]
            [[ ! -e "$executed" ]]
            ACTIVE_DEADLINE=0

            candidate_capture="$fixture/candidate-deadlines"
            set +e
            (
              trap - EXIT
              write_transaction_marker() { :; }
              run_with_timeout() {
                printf '%s %s %s %s\n' \
                  "$OUTAGE_STARTED" "$OUTAGE_DEADLINE" \
                  "$CANDIDATE_DEADLINE" "$ACTIVE_DEADLINE" \
                  >"$candidate_capture"
                return 1
              }
              CURRENT_PID=101
              set -e
              switch_release_pair v1.2.3-uat.16
            )
            switch_status=$?
            set -e
            [[ "$switch_status" -ne 0 ]]
            read -r outage_start outage_deadline candidate_deadline active_deadline \
              <"$candidate_capture"
            ((outage_deadline - outage_start == OUTAGE_LIMIT_SECONDS))
            ((candidate_deadline - outage_start ==
              OUTAGE_LIMIT_SECONDS - RECOVERY_RESERVE_SECONDS))
            ((active_deadline == candidate_deadline))

            recovery_capture="$fixture/recovery-deadline"
            OUTAGE_STARTED="$SECONDS"
            OUTAGE_DEADLINE=$((OUTAGE_STARTED + OUTAGE_LIMIT_SECONDS))
            CANDIDATE_DEADLINE=$((OUTAGE_DEADLINE - RECOVERY_RESERVE_SECONDS))
            ACTIVE_DEADLINE="$CANDIDATE_DEADLINE"
            TRANSACTION_ACTIVE=true
            TRANSACTION_COMMITTED=false
            CURRENT_PID=101
            CURRENT_FLYWAY_SHA="$(printf 'a%.0s' {1..64})"
            set +e
            (
              trap - EXIT
              run_with_timeout() {
                printf '%s\n' "$ACTIVE_DEADLINE" >"$recovery_capture"
                return 0
              }
              service_stopped_and_port_free() { return 0; }
              flyway_snapshot() { printf '%.0s0' {1..64}; printf '\n'; }
              false
              recover_on_failure
            )
            recovery_status=$?
            set -e
            [[ "$recovery_status" -ne 0 ]]
            [[ "$(cat "$recovery_capture")" == "$OUTAGE_DEADLINE" ]]
            """
        )


if __name__ == "__main__":
    unittest.main()
