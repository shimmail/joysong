import ast
import hashlib
import importlib.util
import io
import json
import os
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "ci" / "package-release.py"
SPEC = importlib.util.spec_from_file_location("package_release", str(SCRIPT))
PACKAGE_RELEASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACKAGE_RELEASE)


class PackageReleaseTest(unittest.TestCase):
    tag = "v0.1.0-uat.1"
    commit = "a" * 40
    run_id = "123456789"

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.jar = self.root / "server.jar"
        with zipfile.ZipFile(str(self.jar), "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("BOOT-INF/classes/application.yml", "spring:\n  application:\n    name: joysong\n")
            archive.writestr(
                "BOOT-INF/classes/db/migration/B33__baseline.sql",
                "CREATE TABLE baseline(id BIGINT PRIMARY KEY);\n",
            )
            archive.writestr(
                "BOOT-INF/classes/db/migration/V40__fixture.sql",
                "ALTER TABLE baseline ADD COLUMN name VARCHAR(64);\n",
            )

        self.admin = self.root / "admin"
        (self.admin / "assets").mkdir(parents=True)
        (self.admin / "index.html").write_text(
            "<!doctype html><div id=\"root\"></div>\n", encoding="utf-8"
        )
        (self.admin / "assets" / "app.js").write_text(
            "console.log('uat');\n", encoding="utf-8"
        )
        self.sbom = self.root / "sbom.json"
        self.sbom.write_text(
            json.dumps({"bomFormat": "CycloneDX", "specVersion": "1.6"}) + "\n",
            encoding="utf-8",
        )
        self.scan = self.root / "scan.json"
        self.scan.write_text(json.dumps({"Results": []}) + "\n", encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()

    def build(self, output_name="release"):
        output = self.root / output_name
        return PACKAGE_RELEASE.build_release(
            output,
            self.tag,
            self.commit,
            self.run_id,
            self.jar,
            self.admin,
            self.sbom,
            self.scan,
        )

    @staticmethod
    def read_payload(bundle):
        result = {}
        with tarfile.open(str(bundle), "r:gz") as archive:
            for member in archive.getmembers():
                stream = archive.extractfile(member)
                result[member.name] = stream.read()
        return result

    @staticmethod
    def write_payload(bundle, payload):
        with tarfile.open(str(bundle), "w:gz") as archive:
            for name in sorted(payload):
                content = payload[name]
                info = tarfile.TarInfo(name)
                info.size = len(content)
                info.mode = 0o644
                archive.addfile(info, io.BytesIO(content))

    def test_cli_builds_exact_self_verifying_bundle_and_sidecar(self):
        output = self.root / "cli-output"
        command = [
            sys.executable,
            str(SCRIPT),
            "--output",
            str(output),
            "--tag",
            self.tag,
            "--commit",
            self.commit,
            "--run-id",
            self.run_id,
            "--jar",
            str(self.jar),
            "--admin-dir",
            str(self.admin),
            "--sbom",
            str(self.sbom),
            "--scan-report",
            str(self.scan),
        ]
        completed = subprocess.run(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            universal_newlines=True,
        )
        self.assertEqual(0, completed.returncode, completed.stderr)
        outputs = dict(line.split("=", 1) for line in completed.stdout.splitlines())
        bundle = Path(outputs["BUNDLE_PATH"])
        sidecar = Path(outputs["BUNDLE_SHA256_FILE"])
        digest = outputs["BUNDLE_SHA256"]

        self.assertEqual("joysong-uat-{0}.tar.gz".format(self.tag), bundle.name)
        self.assertEqual(bundle.name + ".sha256", sidecar.name)
        self.assertEqual({bundle.name, sidecar.name}, {path.name for path in output.iterdir()})
        self.assertEqual(PACKAGE_RELEASE.sha256(bundle), digest)
        self.assertRegex(digest, r"^[0-9a-f]{64}$")
        self.assertEqual(
            "{0}  {1}\n".format(digest, bundle.name),
            sidecar.read_text(encoding="ascii"),
        )

        with tarfile.open(str(bundle), "r:gz") as archive:
            members = archive.getmembers()
            self.assertEqual(
                sorted(PACKAGE_RELEASE.PAYLOAD_NAMES),
                sorted(member.name for member in members),
            )
            self.assertTrue(all(member.isfile() for member in members))
            self.assertTrue(all("/" not in member.name for member in members))

        payload = self.read_payload(bundle)
        checksums = PACKAGE_RELEASE.parse_checksums(
            payload[PACKAGE_RELEASE.CHECKSUM_NAME]
        )
        for name, expected in checksums.items():
            self.assertEqual(hashlib.sha256(payload[name]).hexdigest(), expected)

        release = json.loads(payload[PACKAGE_RELEASE.RELEASE_NAME].decode("utf-8"))
        manifest = json.loads(payload[PACKAGE_RELEASE.MANIFEST_NAME].decode("utf-8"))
        expected_identity = {
            "schemaVersion": 1,
            "environment": "uat",
            "tag": self.tag,
            "commit": self.commit,
            "runId": self.run_id,
            "databaseMigrationsSha256": PACKAGE_RELEASE.database_migrations_sha256(
                self.jar
            ),
        }
        for key, value in expected_identity.items():
            self.assertEqual(value, release[key])
            self.assertEqual(value, manifest[key])
        self.assertEqual("UAT Candidate", release["status"])
        self.assertEqual(
            set(PACKAGE_RELEASE.ARTIFACT_NAMES), set(manifest["artifacts"])
        )
        for name, details in manifest["artifacts"].items():
            self.assertEqual(hashlib.sha256(payload[name]).hexdigest(), details["sha256"])
            self.assertEqual(len(payload[name]), details["size"])

        with tarfile.open(
            fileobj=io.BytesIO(payload[PACKAGE_RELEASE.ADMIN_NAME]), mode="r:gz"
        ) as admin_archive:
            admin_members = admin_archive.getmembers()
            self.assertIn("admin/index.html", [member.name for member in admin_members])
            self.assertTrue(
                all(member.isfile() or member.isdir() for member in admin_members)
            )

        verification = PACKAGE_RELEASE.validate_bundle(
            bundle, self.tag, self.commit, self.run_id
        )
        self.assertEqual(digest, verification["bundleSha256"])

    def test_bundle_is_reproducible(self):
        first, first_digest, unused = self.build("first")
        second, second_digest, unused = self.build("second")
        self.assertEqual(first_digest, second_digest)
        self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_identity_validation_is_strict(self):
        PACKAGE_RELEASE.validate_identity(
            self.tag, self.commit, str(PACKAGE_RELEASE.MAX_RUN_ID)
        )
        invalid_values = (
            ("v01.1.0-uat.1", self.commit, self.run_id),
            ("v0.1.0-uat.01", self.commit, self.run_id),
            ("v0.1.0", self.commit, self.run_id),
            (self.tag, "A" * 40, self.run_id),
            (self.tag, self.commit[:-1], self.run_id),
            (self.tag, self.commit, "0"),
            (self.tag, self.commit, "+1"),
            (self.tag, self.commit, str(PACKAGE_RELEASE.MAX_RUN_ID + 1)),
        )
        for tag, commit, run_id in invalid_values:
            with self.subTest(tag=tag, commit=commit, run_id=run_id):
                with self.assertRaises(ValueError):
                    PACKAGE_RELEASE.validate_identity(tag, commit, run_id)

    def test_rejects_non_json_and_duplicate_json_input(self):
        self.scan.write_text('{"result":1,"result":2}\n', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "duplicate key"):
            self.build()
        self.scan.write_text("not-json\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "valid UTF-8 JSON"):
            self.build()

    def test_rejects_admin_symlink(self):
        link = self.admin / "assets" / "outside.js"
        try:
            link.symlink_to(self.root / "outside.js")
        except (NotImplementedError, OSError) as error:
            self.skipTest("symlink creation is unavailable: {0}".format(error))
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.build()

    @unittest.skipUnless(hasattr(os, "mkfifo"), "FIFO creation requires POSIX")
    def test_rejects_admin_special_file(self):
        fifo = self.admin / "assets" / "unsafe.fifo"
        os.mkfifo(str(fifo))
        with self.assertRaisesRegex(ValueError, "special file"):
            self.build()

    def test_rejects_regular_file_and_member_limits(self):
        with mock.patch.object(PACKAGE_RELEASE, "MAX_SBOM_BYTES", 4):
            with self.assertRaisesRegex(ValueError, "SBOM exceeds"):
                self.build("sbom-limit")
        with mock.patch.object(PACKAGE_RELEASE, "MAX_ADMIN_MEMBERS", 1):
            with self.assertRaisesRegex(ValueError, "too many members"):
                self.build("admin-limit")

    def test_rejects_tampered_payload_and_extra_member(self):
        bundle, unused, unused_sidecar = self.build()
        payload = self.read_payload(bundle)
        tampered_directory = self.root / "tampered"
        tampered_directory.mkdir()
        tampered = tampered_directory / bundle.name
        payload[PACKAGE_RELEASE.SCAN_REPORT_NAME] = b'{"tampered":true}\n'
        self.write_payload(tampered, payload)
        with self.assertRaisesRegex(ValueError, "SHA256SUMS mismatch"):
            PACKAGE_RELEASE.validate_bundle(
                tampered, self.tag, self.commit, self.run_id
            )

        payload["unexpected.txt"] = b"unexpected\n"
        extra_directory = self.root / "extra"
        extra_directory.mkdir()
        extra = extra_directory / bundle.name
        self.write_payload(extra, payload)
        with self.assertRaisesRegex(ValueError, "too many members"):
            PACKAGE_RELEASE.validate_bundle(extra, self.tag, self.commit, self.run_id)

    def test_recomputes_migration_digest_instead_of_trusting_metadata(self):
        bundle, unused, unused_sidecar = self.build()
        payload = self.read_payload(bundle)
        release = json.loads(payload[PACKAGE_RELEASE.RELEASE_NAME].decode("utf-8"))
        manifest = json.loads(payload[PACKAGE_RELEASE.MANIFEST_NAME].decode("utf-8"))
        forged_digest = "0" * 64
        release["databaseMigrationsSha256"] = forged_digest
        manifest["databaseMigrationsSha256"] = forged_digest
        payload[PACKAGE_RELEASE.RELEASE_NAME] = (
            json.dumps(release, indent=2, sort_keys=True) + "\n"
        ).encode("utf-8")
        manifest["artifacts"][PACKAGE_RELEASE.RELEASE_NAME] = {
            "sha256": hashlib.sha256(
                payload[PACKAGE_RELEASE.RELEASE_NAME]
            ).hexdigest(),
            "size": len(payload[PACKAGE_RELEASE.RELEASE_NAME]),
        }
        payload[PACKAGE_RELEASE.MANIFEST_NAME] = (
            json.dumps(manifest, indent=2, sort_keys=True) + "\n"
        ).encode("utf-8")
        payload[PACKAGE_RELEASE.CHECKSUM_NAME] = "".join(
            "{0}  {1}\n".format(hashlib.sha256(payload[name]).hexdigest(), name)
            for name in sorted(PACKAGE_RELEASE.CHECKSUM_NAMES)
        ).encode("ascii")

        forged_directory = self.root / "forged"
        forged_directory.mkdir()
        forged = forged_directory / bundle.name
        self.write_payload(forged, payload)
        with self.assertRaisesRegex(ValueError, "identity differs"):
            PACKAGE_RELEASE.validate_bundle(
                forged, self.tag, self.commit, self.run_id
            )

    def test_source_uses_python_36_compatible_syntax(self):
        source = SCRIPT.read_text(encoding="utf-8")
        ast.parse(source, filename=str(SCRIPT), feature_version=(3, 6))
        self.assertNotIn("from __future__ import annotations", source)
        self.assertNotIn('filter="data"', source)


if __name__ == "__main__":
    unittest.main()
