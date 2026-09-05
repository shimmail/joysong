from __future__ import print_function

import ast
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import textwrap
import unittest
import zipfile
from pathlib import Path
from unittest import mock


DEPLOY_ROOT = Path(__file__).resolve().parents[1]
PACKAGER = DEPLOY_ROOT / "ci" / "package-release.py"
HOST_VERIFIER = DEPLOY_ROOT / "host" / "verify-uat-release.py"
HOST_ENTRY = DEPLOY_ROOT / "host" / "joysong-uat-deploy"
WORKFLOW = DEPLOY_ROOT.parents[1] / ".github" / "workflows" / "uat-candidate.yml"

EXPECTED_BUNDLE_MEMBERS = {
    "joysong-server.jar",
    "joysong-admin.tar.gz",
    "manifest.json",
    "release.json",
    "sbom.cdx.json",
    "scan-report.json",
    "SHA256SUMS",
}
EXPECTED_ARTIFACT_NAMES = EXPECTED_BUNDLE_MEMBERS - {"manifest.json", "SHA256SUMS"}
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

# The two programs intentionally use audience-specific names. Keeping this map
# explicit makes every resource ceiling reviewable and prevents a new limit
# from silently existing on only one side of the release boundary.
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


def evaluate_integer(node):
    if hasattr(ast, "Constant") and isinstance(node, ast.Constant):
        if type(node.value) is int:
            return node.value
    if node.__class__.__name__ == "Num" and type(node.n) is int:
        return node.n
    if isinstance(node, ast.BinOp):
        left = evaluate_integer(node.left)
        right = evaluate_integer(node.right)
        if isinstance(node.op, ast.Mult):
            return left * right
        if isinstance(node.op, ast.Add):
            return left + right
        if isinstance(node.op, ast.Sub):
            return left - right
    raise ValueError("not a static integer expression")


def parsed_limits(path):
    source = path.read_text(encoding="utf-8")
    tree = ast.parse(source, filename=str(path))
    limits = {}
    for statement in tree.body:
        if not isinstance(statement, ast.Assign) or len(statement.targets) != 1:
            continue
        target = statement.targets[0]
        if not isinstance(target, ast.Name) or not target.id.startswith("MAX_"):
            continue
        limits[target.id] = evaluate_integer(statement.value)
    loaded_names = {
        node.id
        for node in ast.walk(tree)
        if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Load)
    }
    return limits, loaded_names


def parsed_regexes_and_collections(path):
    source = path.read_text(encoding="utf-8")
    tree = ast.parse(source, filename=str(path))
    values = {}
    regexes = {}

    def static_value(node):
        if isinstance(node, ast.Constant) and type(node.value) in (int, str):
            return node.value
        if node.__class__.__name__ == "Str":
            return node.s
        if isinstance(node, ast.Name) and node.id in values:
            return values[node.id]
        if isinstance(node, ast.Tuple):
            return tuple(static_value(element) for element in node.elts)
        if isinstance(node, ast.Set):
            return {static_value(element) for element in node.elts}
        if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Sub):
            return static_value(node.left) - static_value(node.right)
        raise ValueError("not a supported static contract")

    for statement in tree.body:
        if not isinstance(statement, ast.Assign) or len(statement.targets) != 1:
            continue
        target = statement.targets[0]
        if not isinstance(target, ast.Name):
            continue
        try:
            values[target.id] = static_value(statement.value)
        except ValueError:
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
            regexes[target.id] = static_value(call.args[0])
    return values, regexes


def workflow_zip_extractor():
    source = WORKFLOW.read_text(encoding="utf-8")
    match = re.search(
        r"<<'PY_EXTRACT_UAT_ARTIFACT'\n(?P<body>.*?)\n\s*PY_EXTRACT_UAT_ARTIFACT$",
        source,
        re.MULTILINE | re.DOTALL,
    )
    if match is None:
        raise AssertionError("workflow bounded ZIP extractor is missing")
    return textwrap.dedent(match.group("body")) + "\n"


class Capacity:
    def __init__(self, available=1024 * 1024 * 1024):
        self.f_bavail = available
        self.f_frsize = 1


def migration_digest(jar_path):
    digest = hashlib.sha256()
    with zipfile.ZipFile(str(jar_path), "r") as archive:
        names = sorted(
            entry.filename
            for entry in archive.infolist()
            if "/db/migration/" in "/" + entry.filename
            and not entry.filename.endswith("/")
        )
        for name in names:
            digest.update(name.encode("utf-8"))
            digest.update(b"\0")
            digest.update(hashlib.sha256(archive.read(name)).digest())
    return digest.hexdigest()


class ReleaseCrossContractTest(unittest.TestCase):
    tag = "v1.2.3-uat.42"
    commit = "a" * 40
    run_id = "4242"

    def run_command(self, command):
        return subprocess.run(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            universal_newlines=True,
            timeout=30,
        )

    def create_test_jar(self, path):
        with zipfile.ZipFile(str(path), "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(
                "BOOT-INF/classes/db/migration/V40__test.sql",
                "CREATE TABLE verifier_test(id BIGINT PRIMARY KEY);\n",
            )

    def test_packaged_bundle_is_accepted_and_expanded_by_host_verifier(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar = root / "joysong-server.jar"
            with zipfile.ZipFile(str(jar), "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr(
                    "BOOT-INF/classes/application.yml",
                    "spring:\n  application:\n    name: joysong\n",
                )
                archive.writestr(
                    "BOOT-INF/classes/db/migration/V40__first.sql",
                    "CREATE TABLE first_table(id BIGINT PRIMARY KEY);\n",
                )
                archive.writestr(
                    "BOOT-INF/classes/db/migration/V41__second.sql",
                    "ALTER TABLE first_table ADD COLUMN name VARCHAR(64);\n",
                )

            admin = root / "admin-build"
            (admin / "assets").mkdir(parents=True)
            (admin / "index.html").write_text(
                "<!doctype html><div id=\"root\"></div>\n", encoding="utf-8"
            )
            (admin / "assets" / "app.js").write_text(
                "console.log('uat');\n", encoding="utf-8"
            )
            sbom = root / "sbom.cdx.json"
            sbom.write_text(
                json.dumps({"bomFormat": "CycloneDX", "specVersion": "1.6"}) + "\n",
                encoding="utf-8",
            )
            scan = root / "scan-report.json"
            scan.write_text(
                json.dumps({"SchemaVersion": 2, "Results": []}) + "\n",
                encoding="utf-8",
            )

            output = root / "output"
            packaged = self.run_command(
                [
                    sys.executable,
                    str(PACKAGER),
                    "--output",
                    str(output),
                    "--tag",
                    self.tag,
                    "--commit",
                    self.commit,
                    "--run-id",
                    self.run_id,
                    "--jar",
                    str(jar),
                    "--admin-dir",
                    str(admin),
                    "--sbom",
                    str(sbom),
                    "--scan-report",
                    str(scan),
                ]
            )
            self.assertEqual(0, packaged.returncode, packaged.stderr)
            package_output = dict(
                line.split("=", 1) for line in packaged.stdout.splitlines()
            )
            bundle = Path(package_output["BUNDLE_PATH"])
            bundle_sha = package_output["BUNDLE_SHA256"]

            staging = root / "verified"
            verified = self.run_command(
                [
                    sys.executable,
                    str(HOST_VERIFIER),
                    str(bundle.resolve()),
                    str(staging.resolve()),
                    self.tag,
                    self.commit,
                    self.run_id,
                    bundle_sha,
                ]
            )
            self.assertEqual(0, verified.returncode, verified.stderr)
            verify_output = dict(
                line.split("=", 1) for line in verified.stdout.splitlines()
            )

            expected_migration_digest = migration_digest(jar)
            manifest = json.loads(
                (staging / "manifest.json").read_text(encoding="utf-8")
            )
            self.assertEqual(bundle_sha, verify_output["BUNDLE_SHA256"])
            self.assertEqual(
                expected_migration_digest,
                verify_output["DATABASE_MIGRATIONS_SHA256"],
            )
            self.assertEqual(
                expected_migration_digest,
                manifest["databaseMigrationsSha256"],
            )
            self.assertEqual(
                hashlib.sha256(jar.read_bytes()).hexdigest(),
                hashlib.sha256((staging / "joysong-server.jar").read_bytes()).hexdigest(),
            )
            self.assertEqual(
                (admin / "index.html").read_bytes(),
                (staging / "admin" / "index.html").read_bytes(),
            )
            self.assertEqual(
                (admin / "assets" / "app.js").read_bytes(),
                (staging / "admin" / "assets" / "app.js").read_bytes(),
            )

            migration_only = self.run_command(
                [
                    sys.executable,
                    str(HOST_VERIFIER),
                    "migration-sha",
                    str((staging / "joysong-server.jar").resolve()),
                ]
            )
            self.assertEqual(0, migration_only.returncode, migration_only.stderr)
            self.assertEqual(expected_migration_digest, migration_only.stdout.strip())

    def test_host_verifier_streams_tar_headers_and_preflights_zip_directory(self):
        source = HOST_VERIFIER.read_text(encoding="utf-8")
        self.assertNotIn(".getmembers()", source)
        self.assertGreaterEqual(source.count('tarfile.open('), 2)
        self.assertGreaterEqual(source.count('"r|gz"'), 2)
        self.assertLess(
            source.index("expected_member_count = validate_zip_directory(path)"),
            source.index('jar = zipfile.ZipFile(path, "r")'),
        )

    def test_host_verifier_rejects_zip64_marker_before_zipfile(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / "server.jar"
            self.create_test_jar(jar)
            payload = bytearray(jar.read_bytes())
            eocd = payload.rfind(b"PK\x05\x06")
            self.assertGreaterEqual(eocd, 20)
            payload[eocd - 20 : eocd - 16] = b"PK\x06\x07"
            jar.write_bytes(payload)

            result = self.run_command(
                [sys.executable, str(HOST_VERIFIER), "migration-sha", str(jar.resolve())]
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("ZIP64 is unsupported", result.stderr)

    def test_host_verifier_rejects_saturated_zip_member_count(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / "server.jar"
            self.create_test_jar(jar)
            payload = bytearray(jar.read_bytes())
            eocd = payload.rfind(b"PK\x05\x06")
            self.assertGreaterEqual(eocd, 0)
            payload[eocd + 8 : eocd + 12] = b"\xff\xff\xff\xff"
            jar.write_bytes(payload)

            result = self.run_command(
                [sys.executable, str(HOST_VERIFIER), "migration-sha", str(jar.resolve())]
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("ZIP64 or member count is unsupported", result.stderr)

    def test_all_resource_limits_match_through_explicit_name_map(self):
        packager_limits, packager_loaded = parsed_limits(PACKAGER)
        verifier_limits, verifier_loaded = parsed_limits(HOST_VERIFIER)
        packager_resource_limits = {
            name: value
            for name, value in packager_limits.items()
            if name != "MAX_RUN_ID"
        }
        expected_verifier_names = set(RESOURCE_LIMIT_NAME_MAP.values())
        problems = []

        if set(packager_resource_limits) != set(RESOURCE_LIMIT_NAME_MAP):
            problems.append(
                "packager resource constants differ from the mapping: missing={0}, "
                "unexpected={1}".format(
                    sorted(set(RESOURCE_LIMIT_NAME_MAP) - set(packager_resource_limits)),
                    sorted(set(packager_resource_limits) - set(RESOURCE_LIMIT_NAME_MAP)),
                )
            )
        if set(verifier_limits) != expected_verifier_names:
            problems.append(
                "verifier resource constants differ from the mapping: missing={0}, "
                "unexpected={1}".format(
                    sorted(expected_verifier_names - set(verifier_limits)),
                    sorted(set(verifier_limits) - expected_verifier_names),
                )
            )

        for packager_name, verifier_name in sorted(
            RESOURCE_LIMIT_NAME_MAP.items()
        ):
            if packager_name not in packager_limits or verifier_name not in verifier_limits:
                continue
            if packager_limits[packager_name] != verifier_limits[verifier_name]:
                problems.append(
                    "{0}={1} does not match {2}={3}".format(
                        packager_name,
                        packager_limits[packager_name],
                        verifier_name,
                        verifier_limits[verifier_name],
                    )
                )
            if packager_name not in packager_loaded:
                problems.append(
                    "packager limit is declared but unused: {0}".format(packager_name)
                )
            if verifier_name not in verifier_loaded:
                problems.append(
                    "verifier limit is declared but unused: {0}".format(verifier_name)
                )

        self.assertEqual([], problems, "\n".join(problems))

    def test_identity_and_required_asset_contract_is_exact_across_all_layers(self):
        packager, packager_regexes = parsed_regexes_and_collections(PACKAGER)
        verifier, verifier_regexes = parsed_regexes_and_collections(HOST_VERIFIER)
        packager_limits, unused = parsed_limits(PACKAGER)
        host_source = HOST_ENTRY.read_text(encoding="utf-8")
        workflow_source = WORKFLOW.read_text(encoding="utf-8")

        self.assertEqual(EXPECTED_BUNDLE_MEMBERS, set(packager["PAYLOAD_NAMES"]))
        self.assertEqual(EXPECTED_ARTIFACT_NAMES, set(packager["ARTIFACT_NAMES"]))
        self.assertEqual(EXPECTED_BUNDLE_MEMBERS, verifier["EXPECTED_MEMBERS"])
        self.assertEqual(EXPECTED_ARTIFACT_NAMES, verifier["ARTIFACT_NAMES"])
        self.assertEqual(len(EXPECTED_BUNDLE_MEMBERS), packager["MAX_BUNDLE_MEMBERS"])
        self.assertEqual(len(EXPECTED_BUNDLE_MEMBERS), verifier["MAX_BUNDLE_MEMBERS"])

        self.assertEqual(PYTHON_TAG_PATTERN, packager_regexes["TAG_PATTERN"])
        self.assertEqual(PYTHON_TAG_PATTERN, verifier_regexes["TAG_RE"])
        self.assertEqual(RUN_ID_PATTERN, packager_regexes["RUN_ID_PATTERN"])
        self.assertEqual(RUN_ID_PATTERN, verifier_regexes["RUN_ID_RE"])
        self.assertEqual(RUN_ID_MAX, packager["MAX_RUN_ID"])
        self.assertEqual(RUN_ID_MAX, verifier["RUN_ID_MAX"])
        self.assertIn("TAG_RE='{0}'".format(BASH_TAG_PATTERN), host_source)
        self.assertIn("RUN_ID_RE='{0}'".format(RUN_ID_PATTERN), host_source)
        self.assertIn("readonly RUN_ID_MAX={0}".format(RUN_ID_MAX), host_source)

        tag_assertion = '[[ "$TAG" =~ {0} ]]'.format(BASH_TAG_PATTERN)
        self.assertEqual(2, workflow_source.count(tag_assertion))
        self.assertIn(
            '[[ "$GITHUB_RUN_ID" =~ {0} ]]'.format(RUN_ID_PATTERN),
            workflow_source,
        )
        self.assertIn(
            '[[ "$RUN_ID" =~ {0} ]]'.format(RUN_ID_PATTERN), workflow_source
        )
        self.assertIn(
            "UAT_MAX_RUN_ID: '{0}'".format(RUN_ID_MAX), workflow_source
        )
        for variable in ("GITHUB_RUN_ID", "RUN_ID"):
            self.assertIn(
                '[[ "${{#{0}}}" -lt 19 || "${0}" < "$UAT_MAX_RUN_ID" || '
                '"${0}" == "$UAT_MAX_RUN_ID" ]]'.format(variable),
                workflow_source,
            )
        self.assertIn(
            "UAT_MAX_BUNDLE_BYTES: '{0}'".format(
                packager_limits["MAX_BUNDLE_COMPRESSED_BYTES"]
            ),
            workflow_source,
        )

    def execute_workflow_extractor(
        self,
        archive_path,
        destination,
        bundle_name,
        expected_sha,
        max_bundle=1024,
        available=1024 * 1024 * 1024,
    ):
        arguments = [
            "workflow-extractor",
            str(archive_path),
            str(destination),
            bundle_name,
            expected_sha,
            str(max_bundle),
            str(1024 * 1024),
            "4096",
            "1024",
        ]
        with mock.patch.object(sys, "argv", arguments), mock.patch.object(
            os, "statvfs", return_value=Capacity(available), create=True
        ):
            exec(compile(workflow_zip_extractor(), str(WORKFLOW), "exec"), {})

    @staticmethod
    def write_artifact_zip(path, members, compression=zipfile.ZIP_DEFLATED):
        with zipfile.ZipFile(str(path), "w", compression) as archive:
            for name, content, mode in members:
                info = zipfile.ZipInfo(name)
                info.create_system = 3
                info.external_attr = mode << 16
                info.compress_type = compression
                archive.writestr(info, content)

    def test_workflow_extractor_accepts_only_bounded_regular_bundle_and_sidecar(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle_name = "joysong-uat-v1.2.3-uat.42.tar.gz"
            bundle = b"bounded bundle\n"
            digest = hashlib.sha256(bundle).hexdigest()
            sidecar = "{0}  {1}\n".format(digest, bundle_name).encode("ascii")
            regular = stat.S_IFREG | 0o600
            archive = root / "artifact.zip"
            self.write_artifact_zip(
                archive,
                (
                    (bundle_name, bundle, regular),
                    (bundle_name + ".sha256", sidecar, regular),
                ),
            )
            destination = root / "valid"
            destination.mkdir()

            self.execute_workflow_extractor(
                archive, destination, bundle_name, digest
            )
            self.assertEqual(bundle, (destination / bundle_name).read_bytes())
            self.assertEqual(
                sidecar, (destination / (bundle_name + ".sha256")).read_bytes()
            )

    def test_workflow_extractor_rejects_extra_link_oversize_and_bad_sidecar(self):
        bundle_name = "joysong-uat-v1.2.3-uat.42.tar.gz"
        bundle = b"bounded bundle\n"
        digest = hashlib.sha256(bundle).hexdigest()
        canonical_sidecar = "{0}  {1}\n".format(digest, bundle_name).encode("ascii")
        regular = stat.S_IFREG | 0o600
        link = stat.S_IFLNK | 0o777
        cases = (
            (
                "extra",
                (
                    (bundle_name, bundle, regular),
                    (bundle_name + ".sha256", canonical_sidecar, regular),
                    ("unexpected", b"x", regular),
                ),
                1024,
            ),
            (
                "link",
                (
                    (bundle_name, bundle, link),
                    (bundle_name + ".sha256", canonical_sidecar, regular),
                ),
                1024,
            ),
            (
                "oversize",
                (
                    (bundle_name, bundle, regular),
                    (bundle_name + ".sha256", canonical_sidecar, regular),
                ),
                len(bundle) - 1,
            ),
            (
                "sidecar",
                (
                    (bundle_name, bundle, regular),
                    (bundle_name + ".sha256", b"not canonical\n", regular),
                ),
                1024,
            ),
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for label, members, max_bundle in cases:
                with self.subTest(case=label):
                    archive = root / (label + ".zip")
                    destination = root / label
                    destination.mkdir()
                    self.write_artifact_zip(archive, members)
                    with self.assertRaises(SystemExit):
                        self.execute_workflow_extractor(
                            archive,
                            destination,
                            bundle_name,
                            digest,
                            max_bundle=max_bundle,
                        )

    def test_self_hosted_download_uses_isolated_bounded_tools(self):
        source = WORKFLOW.read_text(encoding="utf-8")
        ast.parse(
            workflow_zip_extractor(),
            filename=str(WORKFLOW),
            feature_version=(3, 6),
        )
        self.assertGreaterEqual(
            source.count("/usr/bin/curl -q --noproxy '*'"), 2
        )
        self.assertIn('--max-filesize "$UAT_MAX_ARTIFACT_ZIP_BYTES"', source)
        self.assertGreaterEqual(source.count("/usr/bin/python3 -I -"), 2)
        self.assertNotRegex(source, r"(?:^|\s)/usr/bin/unzip(?:\s|$)")

    def test_workflow_extractor_checks_crc_and_available_capacity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle_name = "joysong-uat-v1.2.3-uat.42.tar.gz"
            bundle = b"unique CRC payload for UAT Artifact"
            digest = hashlib.sha256(bundle).hexdigest()
            sidecar = "{0}  {1}\n".format(digest, bundle_name).encode("ascii")
            regular = stat.S_IFREG | 0o600
            members = (
                (bundle_name, bundle, regular),
                (bundle_name + ".sha256", sidecar, regular),
            )

            capacity_archive = root / "capacity.zip"
            self.write_artifact_zip(capacity_archive, members)
            capacity_destination = root / "capacity"
            capacity_destination.mkdir()
            with self.assertRaisesRegex(SystemExit, "insufficient capacity"):
                self.execute_workflow_extractor(
                    capacity_archive,
                    capacity_destination,
                    bundle_name,
                    digest,
                    available=0,
                )

            crc_archive = root / "crc.zip"
            self.write_artifact_zip(
                crc_archive, members, compression=zipfile.ZIP_STORED
            )
            damaged = bytearray(crc_archive.read_bytes())
            payload_offset = damaged.index(bundle)
            damaged[payload_offset] ^= 0x01
            crc_archive.write_bytes(damaged)
            crc_destination = root / "crc"
            crc_destination.mkdir()
            with self.assertRaises(zipfile.BadZipFile):
                self.execute_workflow_extractor(
                    crc_archive, crc_destination, bundle_name, digest
                )


if __name__ == "__main__":
    unittest.main()
