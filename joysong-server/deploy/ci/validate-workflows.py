#!/usr/bin/env python3
from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
WORKFLOWS = ROOT / ".github" / "workflows"
FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
MARKDOWN_LINK = re.compile(r"\[[^\]]*\]\(([^)]+)\)")


def main() -> None:
    errors: list[str] = []
    workflow_paths = sorted(WORKFLOWS.glob("*.yml"))
    if not workflow_paths:
        errors.append("no workflows found")
    for path in workflow_paths:
        text = path.read_text(encoding="utf-8")
        for line_number, line in enumerate(text.splitlines(), 1):
            match = re.match(r"\s*-?\s*uses:\s*([^\s#]+)", line)
            if not match:
                continue
            reference = match.group(1)
            if reference.startswith("./"):
                continue
            if "@" not in reference or not FULL_SHA.fullmatch(reference.rsplit("@", 1)[1]):
                errors.append(f"{path}:{line_number}: action is not pinned to a full commit SHA")
        if path.name in {"uat-candidate.yml", "uat-rollback.yml", "prod-deploy.yml"}:
            if "cancel-in-progress: false" not in text:
                errors.append(f"{path}: active deployments must not be cancelled")
        if "channel: stable" in text:
            errors.append(f"{path}: Flutter SDK must be pinned to an exact version")
        for obsolete in ("ALIYUN_DEPLOY_ROLE_ARN", "ALIYUN_OSS_ENDPOINT", "UAT_ANDROID_", "PROD_ANDROID_"):
            if obsolete in text:
                errors.append(f"{path}: obsolete CI configuration name is forbidden: {obsolete}")

    quality_gates = WORKFLOWS / "quality-gates.yml"
    if quality_gates.is_file():
        quality_text = quality_gates.read_text(encoding="utf-8")
        if 'shellcheck -x "${shell_files[@]}"' not in quality_text:
            errors.append("quality-gates.yml: deployment shell assets must run ShellCheck")
        if "joysong-server/deploy/host/joysong-release-dispatch" not in quality_text:
            errors.append("quality-gates.yml: ShellCheck must include the extensionless release dispatcher")
    required = [
        ROOT / ".github/workflows/quality-gates.yml",
        ROOT / ".github/workflows/uat-candidate.yml",
        ROOT / ".github/workflows/uat-promote.yml",
        ROOT / ".github/workflows/uat-rollback.yml",
        ROOT / ".github/workflows/prod-deploy.yml",
        ROOT / ".github/ISSUE_TEMPLATE/uat-acceptance.yml",
        ROOT / "docs/guide/deployment/README.md",
        ROOT / "docs/guide/deployment/CONFIGURATION_REFERENCE.md",
        ROOT / "joysong-server/deploy/ci/package-release.py",
        ROOT / "joysong-server/deploy/ci/publish-and-deploy.sh",
        ROOT / "joysong-server/deploy/ci/verify-uat-acceptance.sh",
        ROOT / "joysong-server/deploy/host/deploy-release.sh",
        ROOT / "joysong-server/deploy/host/joysong-release-dispatch",
        ROOT / "joysong-server/deploy/host/backup-runtime-state.sh",
        ROOT / "joysong-server/deploy/host/baseline-data-manifest.py",
        ROOT / "joysong-server/deploy/host/restore-uat-backup-to-new-db.sh",
        ROOT / "joysong-server/deploy/host/capture-existing-uat-baseline.sh",
        ROOT / "joysong-server/deploy/host/finalize-uat-cutover.sh",
        ROOT / "joysong-server/deploy/host/validate-runtime-config.sh",
        ROOT / "joysong-server/deploy/systemd/joysong@.service",
        ROOT / "joysong-server/deploy/nginx/joysong-uat.conf",
        ROOT / "joysong-server/deploy/nginx/joysong-uat-upstream.conf",
        ROOT / "joysong-server/deploy/nginx/joysong-uat-subdomains.conf",
        ROOT / "joysong-server/deploy/nginx/joysong-default-deny.conf",
    ]
    for path in required:
        if not path.is_file():
            errors.append(f"missing deployment asset: {path.relative_to(ROOT)}")

    bootstrap_script = ROOT / "joysong-server/deploy/host/bootstrap-host.sh"
    if bootstrap_script.is_file():
        bootstrap_text = bootstrap_script.read_text(encoding="utf-8")
        helper_install = (
            'install -o root -g root -m 0755 "$deploy_root/host/baseline-data-manifest.py" '
            '/usr/local/lib/joysong/baseline-data-manifest.py'
        )
        if helper_install not in bootstrap_text:
            errors.append("bootstrap must install the root-owned baseline data manifest helper")

    capture_script = ROOT / "joysong-server/deploy/host/capture-existing-uat-baseline.sh"
    if capture_script.is_file():
        capture_text = capture_script.read_text(encoding="utf-8")
        capture_contract = (
            '(($# == 8))',
            'old_admin="$(readlink -f -- "$old_admin")"',
            '${admin_commit:0:8}',
            '"baselineSourceCommits"',
            'BASELINE_SERVER_COMMIT=$server_commit',
            'BASELINE_ADMIN_COMMIT=$admin_commit',
            'OLD_ADMIN=$old_admin',
            'OLD_ADMIN_COMMIT=$admin_commit',
            'def read_environment(path_value, allow_legacy_lines=False):',
            'previous_assignment_key = None',
            '5bf8aa57fc5a6bc547decf1cc6db63f10deb55a3c6c5df497d631fb3d95e1abf',
            '733e034005783808dcc93b5c3683e47cd536f7d3cc6c1141dae9742304cd7069',
            'provenance = (previous_assignment_key, line_sha256)',
            'provenance in seen_lines',
            'seen_lines.add(provenance)',
            'old_values, ignored_old_lines = read_environment(sys.argv[1], allow_legacy_lines=True)',
            'new_values, ignored_new_lines = read_environment(sys.argv[2])',
            'normalized_old_lines = sorted(ignored_old_lines)',
            'OLD_ENV_IGNORED_LINE_COUNT=$old_env_ignored_line_count',
            'OLD_ENV_IGNORED_LINE_SHA256=$old_env_ignored_line_sha256',
            'data_manifest="$backup_root/DATA_MANIFEST.jsonl"',
            '"$data_manifest_helper" check "$old_uploads"',
            '"$data_manifest_helper" create "$backup_root/data" "$data_manifest"',
            'old-service.txt old-service-status.txt nginx.txt data-migration.txt DATA_MANIFEST.jsonl',
            'DATA_MANIFEST_SHA256=$data_manifest_sha256',
            'DATA_MANIFEST_PATH=$data_manifest',
        )
        for mention in capture_contract:
            if mention not in capture_text:
                errors.append(f"baseline capture provenance contract is missing: {mention}")

    manifest_helper = ROOT / "joysong-server/deploy/host/baseline-data-manifest.py"
    if manifest_helper.is_file():
        helper_text = manifest_helper.read_text(encoding="utf-8")
        helper_contract = (
            'sys.argv[1] not in ("check", "create", "verify")',
            'getattr(os, "geteuid", lambda: 1)() != 0',
            'os.listdir(directory_descriptor)',
            'follow_symlinks=False',
            'entry_stat.st_dev != root_device',
            'entry_stat.st_nlink != 1',
            'base64.b64encode(relative_path).decode("ascii")',
            'records.sort(key=lambda item: item[0])',
            'baseline data changed between manifest scans',
            'baseline data manifest target must not exist',
            'os.fchown(descriptor, 0, 0)',
            'os.fchmod(descriptor, 0o600)',
            'os.fsync(stream.fileno())',
            'os.link(stage, manifest_path, follow_symlinks=False)',
            'baseline data manifest must be a root:root mode 0600 regular non-link file',
            'baseline data manifest does not match both rebuilt scans',
        )
        for mention in helper_contract:
            if mention not in helper_text:
                errors.append(f"baseline data manifest helper contract is missing: {mention}")
        try:
            compile(helper_text, str(manifest_helper), "exec")
        except SyntaxError as error:
            errors.append(f"baseline data manifest helper has invalid Python syntax: {error}")

    finalize_script = ROOT / "joysong-server/deploy/host/finalize-uat-cutover.sh"
    if finalize_script.is_file():
        finalize_text = finalize_script.read_text(encoding="utf-8")
        finalize_contract = (
            '"$(stat -c \'%U:%G:%a\' "$baseline_marker" 2>/dev/null || true)" != "root:root:600"',
            'baseline BACKUP_ROOT must be one direct child of the canonical backup parent',
            'baseline_checksum_names=(',
            'if len(expected_names) != 13:',
            'baseline SHA256SUMS must contain exactly 13 entries',
            'def read_root_only(path_value, label, capture_content=False):',
            'chunks = [] if capture_content else None',
            'checksum_content, _ = read_root_only(checksum_path, "baseline SHA256SUMS", capture_content=True)',
            '"$data_manifest_helper" verify "$baseline_backup_root/data" "$baseline_data_manifest_path"',
            'def read_environment(path_value, allow_legacy_lines=False):',
            'old_values, ignored_old_lines = read_environment(sys.argv[1], allow_legacy_lines=True)',
            'new_values, ignored_new_lines = read_environment(sys.argv[2])',
            'baseline_old_env_ignored_line_count="$(read_baseline_value OLD_ENV_IGNORED_LINE_COUNT)"',
            'baseline_old_env_ignored_line_sha256="$(read_baseline_value OLD_ENV_IGNORED_LINE_SHA256)"',
            'current_old_env_ignored_line_count current_old_env_ignored_line_sha256',
        )
        for mention in finalize_contract:
            if mention not in finalize_text:
                errors.append(f"final cutover baseline-integrity contract is missing: {mention}")
        if finalize_text.count("check_old_live_data_roots") < 4:
            errors.append("final cutover must check live data before stop, after freeze and before final rsync")
        verify_index = finalize_text.find('"$data_manifest_helper" verify')
        maintenance_index = finalize_text.find('install -o root -g root -m 0600 /dev/null "$maintenance_file"')
        if verify_index < 0 or maintenance_index < 0 or verify_index > maintenance_index:
            errors.append("final cutover must verify the baseline manifest before entering maintenance")

    deploy_script = ROOT / "joysong-server/deploy/host/deploy-release.sh"
    if deploy_script.is_file():
        deploy_text = deploy_script.read_text(encoding="utf-8")
        deploy_contract = (
            'baseline_source_commits = data.get("baselineSourceCommits")',
            'if tag == "v0.0.0-uat.0":',
            'release_identity.get("baselineSourceCommits") != baseline_source_commits',
        )
        for mention in deploy_contract:
            if mention not in deploy_text:
                errors.append(f"stored baseline provenance validation is missing: {mention}")

    legacy_names = (
        "CONFIGURATION_GUIDE.md",
        "ALIYUN_DEMO_DEPLOYMENT_GUIDE.md",
        "CLOUD_DEPLOYMENT_GUIDE.md",
    )
    for name in legacy_names:
        legacy_path = ROOT / "docs/guide" / name
        if legacy_path.exists():
            errors.append(f"legacy deployment guide must be removed: {legacy_path.relative_to(ROOT)}")
    for path in (ROOT / "docs").rglob("*.md"):
        text = path.read_text(encoding="utf-8")
        for name in legacy_names:
            if name in text:
                errors.append(f"{path.relative_to(ROOT)}: references retired deployment guide {name}")

    link_documents = (
        ROOT / "docs/guide/deployment/README.md",
        ROOT / "docs/guide/deployment/CONFIGURATION_REFERENCE.md",
        ROOT / "joysong-server/deploy/README.md",
    )
    for path in link_documents:
        if not path.is_file():
            continue
        for match in MARKDOWN_LINK.finditer(path.read_text(encoding="utf-8")):
            target = match.group(1).strip().split("#", 1)[0]
            if not target or re.match(r"^(?:https?://|mailto:)", target):
                continue
            if target.startswith("<") and target.endswith(">"):
                target = target[1:-1]
            resolved = (path.parent / target).resolve()
            try:
                resolved.relative_to(ROOT.resolve())
            except ValueError:
                errors.append(f"{path.relative_to(ROOT)}: link escapes repository: {target}")
                continue
            if not resolved.exists():
                errors.append(f"{path.relative_to(ROOT)}: broken relative link: {target}")

    canonical = ROOT / "docs/guide/deployment/README.md"
    configuration_reference = ROOT / "docs/guide/deployment/CONFIGURATION_REFERENCE.md"
    if canonical.is_file():
        canonical_text = canonical.read_text(encoding="utf-8")
        required_mentions = [
            ".github/workflows/quality-gates.yml",
            ".github/workflows/uat-candidate.yml",
            ".github/workflows/uat-promote.yml",
            ".github/workflows/uat-rollback.yml",
            ".github/workflows/prod-deploy.yml",
            ".github/ISSUE_TEMPLATE/uat-acceptance.yml",
            "joysong-server/deploy/",
        ]
        for mention in required_mentions:
            if mention not in canonical_text:
                errors.append(f"canonical deployment guide does not mention executable asset: {mention}")
    if configuration_reference.is_file():
        configuration_text = configuration_reference.read_text(encoding="utf-8")
        referenced_configuration = set()
        for workflow_path in workflow_paths:
            workflow_text = workflow_path.read_text(encoding="utf-8")
            referenced_configuration.update(
                re.findall(r"\b(?:vars|secrets)\.([A-Z][A-Z0-9_]*)", workflow_text)
            )
        for name in sorted(referenced_configuration):
            if name not in configuration_text:
                errors.append(
                    f"canonical configuration reference does not define workflow setting: {name}"
                )
    if errors:
        raise SystemExit("\n".join(errors))
    print(f"Validated {len(workflow_paths)} workflows and {len(required)} deployment assets.")


if __name__ == "__main__":
    main()
