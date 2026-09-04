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
