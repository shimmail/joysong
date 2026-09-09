#!/usr/bin/env python3
from __future__ import annotations

import ast
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
WORKFLOWS = ROOT / ".github" / "workflows"
FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
USES = re.compile(r"^\s*-?\s*uses:\s*([^\s#]+)", re.MULTILINE)
RUNS_ON = re.compile(r"^\s+runs-on:\s*(.*?)\s*$", re.MULTILINE)
JOB_HEADER = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$", re.MULTILINE)
HOSTED_RUNNERS = {"ubuntu-latest", "windows-latest", "macos-latest"}
EXPECTED_BUNDLE_MEMBERS = {
    "joysong-server.jar",
    "joysong-admin.tar.gz",
    "manifest.json",
    "release.json",
    "sbom.cdx.json",
    "scan-report.json",
    "SHA256SUMS",
}
EXPECTED_MANIFEST_ARTIFACTS = EXPECTED_BUNDLE_MEMBERS - {
    "manifest.json",
    "SHA256SUMS",
}
RESOURCE_LIMIT_NAME_MAP = {
    "MAX_BUNDLE_COMPRESSED_BYTES": "MAX_BUNDLE_BYTES",
    "MAX_BUNDLE_EXPANDED_BYTES": "MAX_OUTER_EXPANDED_BYTES",
    "MAX_BUNDLE_MEMBERS": "MAX_BUNDLE_MEMBERS",
    "MAX_JAR_BYTES": "MAX_JAR_BYTES",
    "MAX_JAR_MEMBERS": "MAX_JAR_MEMBERS",
    "MAX_JAR_EXPANDED_BYTES": "MAX_JAR_EXPANDED_BYTES",
    "MAX_JAR_ENTRY_BYTES": "MAX_JAR_MEMBER_BYTES",
    "MAX_MIGRATION_MEMBERS": "MAX_MIGRATION_MEMBERS",
    "MAX_MIGRATION_EXPANDED_BYTES": "MAX_MIGRATION_BYTES",
    "MAX_MIGRATION_ENTRY_BYTES": "MAX_MIGRATION_MEMBER_BYTES",
    "MAX_ADMIN_ARCHIVE_BYTES": "MAX_ADMIN_BYTES",
    "MAX_ADMIN_MEMBERS": "MAX_ADMIN_MEMBERS",
    "MAX_ADMIN_EXPANDED_BYTES": "MAX_ADMIN_EXPANDED_BYTES",
    "MAX_ADMIN_FILE_BYTES": "MAX_ADMIN_MEMBER_BYTES",
    "MAX_METADATA_BYTES": "MAX_SMALL_METADATA_BYTES",
    "MAX_SBOM_BYTES": "MAX_METADATA_BYTES",
    "MAX_SCAN_REPORT_BYTES": "MAX_METADATA_BYTES",
    "MAX_MEMBER_NAME_BYTES": "MAX_MEMBER_NAME_BYTES",
}
PYTHON_TAG_PATTERN = (
    r"^v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
    r"-uat\.(?:0|[1-9][0-9]*)$"
)
BASH_TAG_PATTERN = (
    r"^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"-uat\.(0|[1-9][0-9]*)$"
)
RUN_ID_PATTERN = r"^[1-9][0-9]{0,18}$"
RUN_ID_MAX = 9223372036854775807
WORKFLOW_LIMITS = {
    "UAT_MAX_RUN_ID": RUN_ID_MAX,
    "UAT_MAX_ARTIFACT_ZIP_BYTES": 1088 * 1024 * 1024,
    "UAT_MAX_SIDECAR_BYTES": 4096,
    "UAT_MIN_FREE_AFTER_EXTRACT_BYTES": 64 * 1024 * 1024,
}


def read_required(path: Path, errors: list[str]) -> str:
    if not path.is_file():
        errors.append(f"missing deployment asset: {path.relative_to(ROOT)}")
        return ""
    return path.read_text(encoding="utf-8")


def require_snippets(
    path: Path, text: str, snippets: tuple[str, ...], errors: list[str]
) -> None:
    for snippet in snippets:
        if snippet not in text:
            errors.append(f"{path.relative_to(ROOT)}: missing required contract: {snippet}")


class StaticValueError(ValueError):
    pass


def evaluate_static(node: ast.AST, values: dict[str, object]) -> object:
    if isinstance(node, ast.Constant) and type(node.value) in (int, str):
        return node.value
    if isinstance(node, ast.Name) and node.id in values:
        return values[node.id]
    if isinstance(node, ast.Tuple):
        return tuple(evaluate_static(element, values) for element in node.elts)
    if isinstance(node, ast.List):
        return [evaluate_static(element, values) for element in node.elts]
    if isinstance(node, ast.Set):
        return {evaluate_static(element, values) for element in node.elts}
    if isinstance(node, ast.BinOp):
        left = evaluate_static(node.left, values)
        right = evaluate_static(node.right, values)
        if isinstance(node.op, ast.Mult) and type(left) is int and type(right) is int:
            return left * right
        if isinstance(node.op, ast.Add) and type(left) is int and type(right) is int:
            return left + right
        if isinstance(node.op, ast.Sub):
            if type(left) is int and type(right) is int:
                return left - right
            if isinstance(left, set) and isinstance(right, set):
                return left - right
    raise StaticValueError("not a supported static value")


def parse_module_contract(
    path: Path, errors: list[str]
) -> tuple[dict[str, object], dict[str, str], set[str]]:
    try:
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    except (OSError, SyntaxError) as error:
        errors.append(f"{path.relative_to(ROOT)}: cannot parse release contract: {error}")
        return {}, {}, set()

    values: dict[str, object] = {}
    patterns: dict[str, str] = {}
    for statement in tree.body:
        if not isinstance(statement, ast.Assign) or len(statement.targets) != 1:
            continue
        target = statement.targets[0]
        if not isinstance(target, ast.Name):
            continue
        try:
            values[target.id] = evaluate_static(statement.value, values)
        except StaticValueError:
            pass
        call = statement.value
        if (
            isinstance(call, ast.Call)
            and isinstance(call.func, ast.Attribute)
            and isinstance(call.func.value, ast.Name)
            and call.func.value.id == "re"
            and call.func.attr == "compile"
            and len(call.args) == 1
        ):
            try:
                pattern = evaluate_static(call.args[0], values)
            except StaticValueError:
                continue
            if isinstance(pattern, str):
                patterns[target.id] = pattern

    loaded = {
        node.id
        for node in ast.walk(tree)
        if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Load)
    }
    return values, patterns, loaded


def require_exact_names(
    path: Path,
    label: str,
    actual: object,
    expected: set[str],
    errors: list[str],
) -> None:
    if not isinstance(actual, (tuple, list, set)):
        errors.append(f"{path.relative_to(ROOT)}: {label} is not a static collection")
        return
    names = list(actual)
    if len(names) != len(expected) or set(names) != expected:
        errors.append(
            f"{path.relative_to(ROOT)}: {label} must be exactly {sorted(expected)}, "
            f"got {sorted(names)}"
        )


def validate_release_cross_contract(
    packager_path: Path,
    verifier_path: Path,
    host_entry_path: Path,
    candidate_path: Path,
    candidate_text: str,
    errors: list[str],
) -> None:
    packager, packager_patterns, packager_loaded = parse_module_contract(
        packager_path, errors
    )
    verifier, verifier_patterns, verifier_loaded = parse_module_contract(
        verifier_path, errors
    )

    require_exact_names(
        packager_path,
        "PAYLOAD_NAMES",
        packager.get("PAYLOAD_NAMES"),
        EXPECTED_BUNDLE_MEMBERS,
        errors,
    )
    require_exact_names(
        packager_path,
        "ARTIFACT_NAMES",
        packager.get("ARTIFACT_NAMES"),
        EXPECTED_MANIFEST_ARTIFACTS,
        errors,
    )
    require_exact_names(
        verifier_path,
        "EXPECTED_MEMBERS",
        verifier.get("EXPECTED_MEMBERS"),
        EXPECTED_BUNDLE_MEMBERS,
        errors,
    )
    require_exact_names(
        verifier_path,
        "ARTIFACT_NAMES",
        verifier.get("ARTIFACT_NAMES"),
        EXPECTED_MANIFEST_ARTIFACTS,
        errors,
    )

    packager_limits = {
        name: value
        for name, value in packager.items()
        if name.startswith("MAX_") and name != "MAX_RUN_ID"
    }
    verifier_limits = {
        name: value for name, value in verifier.items() if name.startswith("MAX_")
    }
    if set(packager_limits) != set(RESOURCE_LIMIT_NAME_MAP):
        errors.append(
            f"{packager_path.relative_to(ROOT)}: resource limits must be exactly "
            f"{sorted(RESOURCE_LIMIT_NAME_MAP)}, got {sorted(packager_limits)}"
        )
    expected_verifier_limits = set(RESOURCE_LIMIT_NAME_MAP.values())
    if set(verifier_limits) != expected_verifier_limits:
        errors.append(
            f"{verifier_path.relative_to(ROOT)}: resource limits must be exactly "
            f"{sorted(expected_verifier_limits)}, got {sorted(verifier_limits)}"
        )
    for packager_name, verifier_name in RESOURCE_LIMIT_NAME_MAP.items():
        if packager_name not in packager_limits or verifier_name not in verifier_limits:
            continue
        if packager_limits[packager_name] != verifier_limits[verifier_name]:
            errors.append(
                f"release limit mismatch: {packager_name}={packager_limits[packager_name]} "
                f"but {verifier_name}={verifier_limits[verifier_name]}"
            )
        if packager_name not in packager_loaded:
            errors.append(f"{packager_path.relative_to(ROOT)}: unused limit {packager_name}")
        if verifier_name not in verifier_loaded:
            errors.append(f"{verifier_path.relative_to(ROOT)}: unused limit {verifier_name}")

    if packager.get("MAX_BUNDLE_MEMBERS") != len(EXPECTED_BUNDLE_MEMBERS):
        errors.append("MAX_BUNDLE_MEMBERS must equal the exact bundle member count")
    if packager_patterns.get("TAG_PATTERN") != PYTHON_TAG_PATTERN:
        errors.append(f"{packager_path.relative_to(ROOT)}: TAG_PATTERN is not canonical")
    if verifier_patterns.get("TAG_RE") != PYTHON_TAG_PATTERN:
        errors.append(f"{verifier_path.relative_to(ROOT)}: TAG_RE is not canonical")
    if packager_patterns.get("RUN_ID_PATTERN") != RUN_ID_PATTERN:
        errors.append(f"{packager_path.relative_to(ROOT)}: RUN_ID_PATTERN is not canonical")
    if verifier_patterns.get("RUN_ID_RE") != RUN_ID_PATTERN:
        errors.append(f"{verifier_path.relative_to(ROOT)}: RUN_ID_RE is not canonical")
    if packager.get("MAX_RUN_ID") != RUN_ID_MAX:
        errors.append(f"{packager_path.relative_to(ROOT)}: MAX_RUN_ID is not signed 64-bit")
    if verifier.get("RUN_ID_MAX") != RUN_ID_MAX:
        errors.append(f"{verifier_path.relative_to(ROOT)}: RUN_ID_MAX is not signed 64-bit")

    host_text = read_required(host_entry_path, errors)
    host_tag = re.search(r"^TAG_RE='([^']+)'$", host_text, re.MULTILINE)
    host_run_id = re.search(r"^RUN_ID_RE='([^']+)'$", host_text, re.MULTILINE)
    host_run_id_max = re.search(r"^readonly RUN_ID_MAX=([0-9]+)$", host_text, re.MULTILINE)
    if host_tag is None or host_tag.group(1) != BASH_TAG_PATTERN:
        errors.append(f"{host_entry_path.relative_to(ROOT)}: TAG_RE is not canonical")
    if host_run_id is None or host_run_id.group(1) != RUN_ID_PATTERN:
        errors.append(f"{host_entry_path.relative_to(ROOT)}: RUN_ID_RE is not canonical")
    if host_run_id_max is None or int(host_run_id_max.group(1)) != RUN_ID_MAX:
        errors.append(f"{host_entry_path.relative_to(ROOT)}: RUN_ID_MAX is not signed 64-bit")

    tag_assertion = f'[[ "$TAG" =~ {BASH_TAG_PATTERN} ]]'
    if candidate_text.count(tag_assertion) != 2:
        errors.append(
            f"{candidate_path.relative_to(ROOT)}: canonical tag assertion must occur twice"
        )
    if f'[[ "$GITHUB_RUN_ID" =~ {RUN_ID_PATTERN} ]]' not in candidate_text:
        errors.append(f"{candidate_path.relative_to(ROOT)}: preflight run ID bound is missing")
    if f'[[ "$RUN_ID" =~ {RUN_ID_PATTERN} ]]' not in candidate_text:
        errors.append(f"{candidate_path.relative_to(ROOT)}: deploy run ID bound is missing")
    for variable in ("GITHUB_RUN_ID", "RUN_ID"):
        maximum_assertion = (
            f'[[ "${{#{variable}}}" -lt 19 || "${variable}" < "$UAT_MAX_RUN_ID" || '
            f'"${variable}" == "$UAT_MAX_RUN_ID" ]]'
        )
        if candidate_text.count(maximum_assertion) != 1:
            errors.append(
                f"{candidate_path.relative_to(ROOT)}: {variable} signed 64-bit bound is missing"
            )
    bundle_limit = packager.get("MAX_BUNDLE_COMPRESSED_BYTES")
    expected_workflow_limits = {
        **WORKFLOW_LIMITS,
        "UAT_MAX_BUNDLE_BYTES": bundle_limit,
    }
    for name, value in expected_workflow_limits.items():
        if candidate_text.count(f"{name}: '{value}'") != 1:
            errors.append(
                f"{candidate_path.relative_to(ROOT)}: {name} is not the canonical {value}"
            )

    require_snippets(
        candidate_path,
        candidate_text,
        (
            "${{ runner.temp }}/release/${{ steps.package.outputs.bundle_name }}\n"
            "            ${{ runner.temp }}/release/${{ steps.package.outputs.bundle_name }}.sha256",
            'expected_names = {bundle_name, sidecar_name}',
            'expected_assets="$(printf \'%s\\n\' "$BUNDLE_NAME" "$BUNDLE_NAME.sha256"',
            "/usr/bin/curl -q --noproxy '*'",
            "/usr/bin/python3 -I -",
            "PY_EXTRACT_UAT_ARTIFACT",
            "archive_details.st_nlink != 1",
            "central_entries != 2",
            "central_size > 8192",
            "len(members) != 2 or len(set(names)) != 2",
            "member.flag_bits & 0x1",
            "file_type not in (0, stat.S_IFREG)",
            "available < expanded + free_reserve",
            "os.O_WRONLY | os.O_CREAT | os.O_EXCL",
            'flags |= os.O_NOFOLLOW',
            "bundle_digest != expected_sha256",
            'raise SystemExit("Artifact SHA-256 sidecar is not canonical")',
            "--max-filesize 1048576",
            '--max-filesize "$UAT_MAX_ARTIFACT_ZIP_BYTES"',
        ),
        errors,
    )
    if "/usr/bin/unzip" in candidate_text or re.search(r"(?:^|\s)unzip(?:\s|$)", candidate_text):
        errors.append(
            f"{candidate_path.relative_to(ROOT)}: unbounded unzip is forbidden on the UAT runner"
        )


def extract_jobs(path: Path, text: str, errors: list[str]) -> dict[str, str]:
    marker = re.search(r"^jobs:\s*$", text, re.MULTILINE)
    if marker is None:
        errors.append(f"{path.relative_to(ROOT)}: jobs mapping is missing")
        return {}
    body = text[marker.end() :]
    matches = list(JOB_HEADER.finditer(body))
    jobs: dict[str, str] = {}
    for index, match in enumerate(matches):
        start = match.start()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(body)
        jobs[match.group(1)] = body[start:end]
    return jobs


def require_runner(
    path: Path,
    jobs: dict[str, str],
    job_name: str,
    expected: str,
    errors: list[str],
) -> None:
    block = jobs.get(job_name)
    if block is None:
        errors.append(f"{path.relative_to(ROOT)}: missing job: {job_name}")
        return
    match = re.search(r"^    runs-on:\s*(.*?)\s*$", block, re.MULTILINE)
    if match is None or match.group(1) != expected:
        actual = match.group(1) if match else "<missing>"
        errors.append(
            f"{path.relative_to(ROOT)}: {job_name} must run on exactly {expected}, got {actual}"
        )


def validate_action_pins(
    workflow_paths: list[Path], workflow_texts: dict[Path, str], errors: list[str]
) -> None:
    for path in workflow_paths:
        for match in USES.finditer(workflow_texts[path]):
            reference = match.group(1)
            if reference.startswith("./"):
                continue
            if "@" not in reference or not FULL_SHA.fullmatch(reference.rsplit("@", 1)[1]):
                line = workflow_texts[path].count("\n", 0, match.start()) + 1
                errors.append(
                    f"{path.relative_to(ROOT)}:{line}: action is not pinned to a full commit SHA"
                )


def validate_pr_runners(
    workflow_paths: list[Path], workflow_texts: dict[Path, str], errors: list[str]
) -> None:
    for path in workflow_paths:
        text = workflow_texts[path]
        if not re.search(r"^  pull_request(?:_target)?:\s*$", text, re.MULTILINE):
            continue
        for runner in RUNS_ON.findall(text):
            if runner not in HOSTED_RUNNERS:
                errors.append(
                    f"{path.relative_to(ROOT)}: pull request workflows may only use "
                    f"GitHub-hosted runners, got {runner or '<mapping/expression>'}"
                )


def validate_quality_gates(path: Path, text: str, errors: list[str]) -> None:
    require_snippets(
        path,
        text,
        (
            "pull_request:\n    branches: [master]",
            "push:\n    branches: [master]",
            "name: Required quality gates",
            "python3 joysong-server/deploy/ci/validate-workflows.py",
            "python3 -m unittest discover -s joysong-server/deploy/tests -p 'test_*.py'",
            "joysong-server/deploy/host/joysong-uat-deploy",
            "shellcheck -x",
            "joysong-server/deploy/ci/run-actionlint.sh",
            "aquasecurity/trivy-action@",
            "format: cyclonedx",
            "subosito/flutter-action@e938fdf56512cc96ef2f93601a5a40bde3801046",
            "flutter-version: '3.44.8'",
            "flutter pub get",
            "flutter analyze",
            "flutter test",
            "flutter build apk --debug --flavor development --dart-define=APP_ENV=development",
            "test -f build/app/outputs/flutter-apk/app-development-debug.apk",
        ),
        errors,
    )
    if text.count("name: Required quality gates") != 1:
        errors.append(
            f"{path.relative_to(ROOT)}: terminal job name must occur exactly once"
        )
    if re.search(r"\b(?:main|default_branch)\b", text):
        errors.append(
            f"{path.relative_to(ROOT)}: active quality gates must target master explicitly"
        )
    jobs = extract_jobs(path, text, errors)
    expected_jobs = {
        "changes",
        "backend",
        "backend-mysql",
        "admin",
        "flutter",
        "infrastructure",
        "security",
        "quality-gates",
    }
    if set(jobs) != expected_jobs:
        errors.append(
            f"{path.relative_to(ROOT)}: expected jobs {sorted(expected_jobs)}, "
            f"got {sorted(jobs)}"
        )
    for job_name in expected_jobs:
        require_runner(path, jobs, job_name, "ubuntu-latest", errors)

    require_snippets(
        path,
        jobs.get("changes", ""),
        (
            "flutter: ${{ steps.scope.outputs.flutter }}",
            "^joysong-flutter/|^\\.github/workflows/quality-gates\\.yml$",
        ),
        errors,
    )
    require_snippets(
        path,
        jobs.get("flutter", ""),
        (
            "needs: changes",
            "if: needs.changes.outputs.flutter == 'true'",
            "working-directory: joysong-flutter",
            "flutter-version: '3.44.8'",
            "flutter pub get",
            "flutter analyze",
            "flutter test",
            "flutter build apk --debug --flavor development --dart-define=APP_ENV=development",
            "test -f build/app/outputs/flutter-apk/app-development-debug.apk",
        ),
        errors,
    )
    require_snippets(
        path,
        jobs.get("quality-gates", ""),
        (
            "needs: [changes, backend, backend-mysql, admin, flutter, infrastructure, security]",
            '(.changes.outputs.flutter == "true" and .flutter.result == "success")',
            '(.changes.outputs.flutter != "true" and .flutter.result == "skipped")',
        ),
        errors,
    )


def validate_uat_candidate(path: Path, text: str, errors: list[str]) -> None:
    require_snippets(
        path,
        text,
        (
            "tags:\n      - 'v*.*.*-uat.*'",
            "group: uat-deployment",
            "cancel-in-progress: false",
            '[[ "$GITHUB_RUN_ATTEMPT" == "1" ]]',
            "--jq .visibility",
            "refs/heads/master:refs/remotes/origin/master",
            "git merge-base --is-ancestor",
            "actions/workflows/uat-candidate.yml/runs?event=push&per_page=100",
            "historical_tag_runs",
            "actions/workflows/quality-gates.yml/runs?branch=master&event=push&head_sha=",
            '.name == "Required quality gates"',
            "--run-id \"$GITHUB_RUN_ID\"",
            "actions/runs/$RUN_ID/artifacts?per_page=100",
            "actions/artifacts/$artifact_id/zip",
            "incoming_root=/var/lib/joysong-deploy/incoming",
            "/usr/local/sbin/joysong-uat-deploy",
            "UAT Candidate 已部署，公网未 Ready",
            "Create or update private Draft Release",
            "Create or update backend and admin acceptance issue",
        ),
        errors,
    )
    if "workflow_dispatch:" in text:
        errors.append(
            f"{path.relative_to(ROOT)}: UAT deployment must be triggered only by a new tag"
        )
    forbidden = (
        r"\bALIYUN_",
        r"\bid-token\b",
        r"cloud-assistant",
        r"publish-and-deploy",
        r"\b(?:flutter|android|ios|apk)\b",
        r"joysong@uat",
        r"\b8081\b",
        r"\bself-hosted\b",
    )
    for pattern in forbidden:
        if re.search(pattern, text, re.IGNORECASE):
            errors.append(
                f"{path.relative_to(ROOT)}: forbidden legacy UAT contract matched: {pattern}"
            )

    jobs = extract_jobs(path, text, errors)
    expected_jobs = {"preflight", "build", "deploy", "publish"}
    if set(jobs) != expected_jobs:
        errors.append(
            f"{path.relative_to(ROOT)}: expected jobs {sorted(expected_jobs)}, "
            f"got {sorted(jobs)}"
        )
    require_runner(path, jobs, "preflight", "ubuntu-latest", errors)
    require_runner(path, jobs, "build", "ubuntu-latest", errors)
    require_runner(path, jobs, "deploy", "joysong-uat-deploy", errors)
    require_runner(path, jobs, "publish", "ubuntu-latest", errors)

    deploy = jobs.get("deploy", "")
    publish = jobs.get("publish", "")
    if USES.search(deploy):
        errors.append(
            f"{path.relative_to(ROOT)}: self-hosted deploy job must not execute actions"
        )
    require_snippets(
        path,
        deploy,
        (
            "permissions:\n      actions: read\n      contents: read",
            "/usr/bin/curl",
            "/usr/bin/python3",
            "/usr/bin/sudo -n /usr/local/sbin/joysong-uat-deploy",
            'preflight "$TAG" "$COMMIT" "$RUN_ID" "$EXPECTED_SHA256"',
            'deploy "$TAG" "$COMMIT" "$RUN_ID" "$EXPECTED_SHA256"',
        ),
        errors,
    )
    if any(
        re.search(pattern, deploy, re.IGNORECASE | re.MULTILINE)
        for pattern in (
            r"^\s+[A-Za-z-]+:\s*write\s*$",
            r"actions/checkout@",
            r"\$\{\{\s*secrets\.",
            r"^\s*(?:/usr/bin/)?jq(?:\s|$)",
        )
    ):
        errors.append(
            f"{path.relative_to(ROOT)}: self-hosted deploy job has expanded permissions or code access"
        )
    require_snippets(
        path,
        publish,
        (
            "needs: [build, deploy]",
            "permissions:\n      actions: read\n      contents: write\n      issues: write",
            "GH_REPO: ${{ github.repository }}",
            "actions/download-artifact@",
            "gh release",
            "gh issue",
            "ADMIN_PASSWORD 已从运行时配置和 bootstrap 输入中原子移除",
        ),
        errors,
    )

    if text.count("actions/upload-artifact@") != 1:
        errors.append(
            f"{path.relative_to(ROOT)}: build must upload exactly one workflow Artifact"
        )
    if text.count("runs-on: joysong-uat-deploy") != 1:
        errors.append(
            f"{path.relative_to(ROOT)}: exactly one job may target the UAT runner"
        )


def validate_acceptance_template(path: Path, text: str, errors: list[str]) -> None:
    require_snippets(
        path,
        text,
        (
            "UAT Candidate 已部署，公网未 Ready",
            "Backend acceptance",
            "Admin acceptance",
            "`current`",
            "`previous`",
            "backups",
            "Flyway",
            "B33 + V34…V40",
            "127.0.0.1:8080",
            "rollback",
        ),
        errors,
    )
    if re.search(
        r"\b(?:flutter|android|ios|apk|mobile|promote|promotion)\b",
        text,
        re.IGNORECASE,
    ):
        errors.append(
            f"{path.relative_to(ROOT)}: acceptance must cover only the current backend and admin UAT path"
        )


def validate_python(errors: list[str]) -> int:
    python_paths = sorted((ROOT / "joysong-server" / "deploy").rglob("*.py"))
    for path in python_paths:
        try:
            ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        except (OSError, SyntaxError) as error:
            errors.append(
                f"{path.relative_to(ROOT)}: invalid deployment Python source: {error}"
            )
    return len(python_paths)


def main() -> None:
    errors: list[str] = []
    acceptance_path = ROOT / ".github/ISSUE_TEMPLATE/uat-acceptance.yml"
    required_assets = (
        ROOT / ".github/actionlint.yaml",
        ROOT / ".github/workflows/quality-gates.yml",
        ROOT / ".github/workflows/uat-candidate.yml",
        acceptance_path,
        ROOT / "docs/guide/deployment/README.md",
        ROOT / "docs/guide/deployment/CONFIGURATION_REFERENCE.md",
        ROOT / "design/ALIYUN_CICD_DEPLOYMENT.puml",
        ROOT / "joysong-server/deploy/README.md",
        ROOT / "joysong-server/deploy/ci/package-release.py",
        ROOT / "joysong-server/deploy/ci/run-actionlint.sh",
        ROOT / "joysong-server/deploy/host/bootstrap-uat-host.sh",
        ROOT / "joysong-server/deploy/host/joysong-uat-deploy",
        ROOT / "joysong-server/deploy/host/verify-uat-release.py",
        ROOT / "joysong-server/deploy/nginx/joysong-public.conf",
        ROOT / "joysong-server/deploy/systemd/joysong-demo.service",
        ROOT / "joysong-server/deploy/tests/test_bootstrap_ubuntu.py",
        ROOT / "joysong-server/deploy/tests/test_package_release.py",
        ROOT / "joysong-server/deploy/tests/test_joysong_uat_deploy.py",
        ROOT / "joysong-server/deploy/tests/test_release_cross_contract.py",
        ROOT / "joysong-server/deploy/tests/test_validate_workflows.py",
    )
    for path in required_assets:
        read_required(path, errors)

    for retired in (
        ROOT / ".github/workflows/uat-promote.yml",
        ROOT / ".github/workflows/uat-rollback.yml",
    ):
        if retired.exists():
            errors.append(
                f"retired UAT workflow must be removed: {retired.relative_to(ROOT)}"
            )

    workflow_paths = sorted(
        {
            *WORKFLOWS.glob("*.yml"),
            *WORKFLOWS.glob("*.yaml"),
        }
    )
    if not workflow_paths:
        errors.append("no workflows found")
    workflow_texts = {
        path: path.read_text(encoding="utf-8") for path in workflow_paths
    }
    validate_action_pins(workflow_paths, workflow_texts, errors)
    validate_pr_runners(workflow_paths, workflow_texts, errors)

    quality_path = ROOT / ".github/workflows/quality-gates.yml"
    candidate_path = ROOT / ".github/workflows/uat-candidate.yml"
    quality_text = workflow_texts.get(quality_path, "")
    candidate_text = workflow_texts.get(candidate_path, "")
    if quality_text:
        validate_quality_gates(quality_path, quality_text, errors)
    if candidate_text:
        validate_uat_candidate(candidate_path, candidate_text, errors)
        validate_release_cross_contract(
            ROOT / "joysong-server/deploy/ci/package-release.py",
            ROOT / "joysong-server/deploy/host/verify-uat-release.py",
            ROOT / "joysong-server/deploy/host/joysong-uat-deploy",
            candidate_path,
            candidate_text,
            errors,
        )
    if acceptance_path.is_file():
        validate_acceptance_template(
            acceptance_path,
            acceptance_path.read_text(encoding="utf-8"),
            errors,
        )

    for path, text in workflow_texts.items():
        if path != candidate_path and re.search(
            r"^\s+runs-on:\s*joysong-uat-deploy\s*$", text, re.MULTILINE
        ):
            errors.append(
                f"{path.relative_to(ROOT)}: UAT runner label is exclusive to uat-candidate.yml"
            )

    python_count = validate_python(errors)
    if errors:
        raise SystemExit("\n".join(errors))
    print(
        f"Validated {len(workflow_paths)} workflows, fast UAT runner isolation, "
        f"and {python_count} deployment Python files."
    )


if __name__ == "__main__":
    main()
