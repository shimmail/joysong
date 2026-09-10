from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
VALIDATOR_PATH = ROOT / "joysong-server/deploy/ci/validate-workflows.py"
QUALITY_WORKFLOW = ROOT / ".github/workflows/quality-gates.yml"
UAT_WORKFLOW = ROOT / ".github/workflows/uat-candidate.yml"
PROD_WORKFLOW = ROOT / ".github/workflows/prod-deploy.yml"

spec = importlib.util.spec_from_file_location("validate_workflows", VALIDATOR_PATH)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Cannot load workflow validator: {VALIDATOR_PATH}")
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


class QualityGatesValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.workflow = QUALITY_WORKFLOW.read_text(encoding="utf-8")

    def validate(self, text: str) -> list[str]:
        errors: list[str] = []
        validator.validate_quality_gates(QUALITY_WORKFLOW, text, errors)
        return errors

    def test_current_flutter_quality_gate_contract_is_valid(self) -> None:
        self.assertEqual([], self.validate(self.workflow))

    def test_requires_flutter_change_detection(self) -> None:
        changed = self.workflow.replace(
            "^joysong-flutter/|^\\.github/workflows/quality-gates\\.yml$",
            "^joysong-mobile/",
        )
        self.assertTrue(self.validate(changed))

    def test_requires_flutter_commands_in_flutter_job(self) -> None:
        changed = self.workflow.replace("          flutter analyze\n", "", 1)
        self.assertTrue(self.validate(changed))

    def test_requires_flutter_in_terminal_gate(self) -> None:
        changed = self.workflow.replace(
            "needs: [changes, backend, backend-mysql, admin, flutter, infrastructure, security]",
            "needs: [changes, backend, backend-mysql, admin, infrastructure, security]",
        )
        self.assertTrue(self.validate(changed))

    def test_requires_flutter_skipped_branch(self) -> None:
        changed = self.workflow.replace(
            '(.changes.outputs.flutter != "true" and .flutter.result == "skipped")',
            '(.changes.outputs.flutter != "true")',
        )
        self.assertTrue(self.validate(changed))

    def test_admin_subpath_build_is_limited_to_uat(self) -> None:
        uat = UAT_WORKFLOW.read_text(encoding="utf-8")
        prod = PROD_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("VITE_BASE_PATH=/ npm run build", uat)
        self.assertIn("VITE_BASE_PATH=/admin/ npm run build -- --outDir dist/admin", uat)
        self.assertIn("dist/admin/index.html", uat)
        self.assertNotIn("VITE_BASE_PATH=/admin/", prod)


if __name__ == "__main__":
    unittest.main()
