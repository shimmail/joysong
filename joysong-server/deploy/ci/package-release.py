#!/usr/bin/env python3
"""Build and verify the immutable Backend/Admin-only UAT deployment bundle."""

import argparse
import gzip
import hashlib
import json
import os
import re
import shutil
import stat
import tarfile
import tempfile
import zipfile
from pathlib import Path


TAG_PATTERN = re.compile(
    r"^v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)-uat\.(?:0|[1-9][0-9]*)$"
)
COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40}$")
RUN_ID_PATTERN = re.compile(r"^[1-9][0-9]{0,18}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
CHECKSUM_LINE_PATTERN = re.compile(r"^([0-9a-f]{64})  ([A-Za-z0-9][A-Za-z0-9._-]*)$")

MAX_BUNDLE_COMPRESSED_BYTES = 1024 * 1024 * 1024
MAX_BUNDLE_EXPANDED_BYTES = 2 * 1024 * 1024 * 1024
MAX_BUNDLE_MEMBERS = 7
MAX_JAR_BYTES = 512 * 1024 * 1024
MAX_JAR_MEMBERS = 50000
MAX_JAR_EXPANDED_BYTES = 2 * 1024 * 1024 * 1024
MAX_JAR_ENTRY_BYTES = 512 * 1024 * 1024
MAX_MIGRATION_MEMBERS = 512
MAX_MIGRATION_EXPANDED_BYTES = 32 * 1024 * 1024
MAX_MIGRATION_ENTRY_BYTES = 2 * 1024 * 1024
MAX_ADMIN_ARCHIVE_BYTES = 512 * 1024 * 1024
MAX_ADMIN_MEMBERS = 10000
MAX_ADMIN_EXPANDED_BYTES = 1024 * 1024 * 1024
MAX_ADMIN_FILE_BYTES = 128 * 1024 * 1024
MAX_METADATA_BYTES = 1024 * 1024
MAX_SBOM_BYTES = 64 * 1024 * 1024
MAX_SCAN_REPORT_BYTES = 64 * 1024 * 1024
MAX_MEMBER_NAME_BYTES = 240
MAX_RUN_ID = 9223372036854775807

SERVER_NAME = "joysong-server.jar"
ADMIN_NAME = "joysong-admin.tar.gz"
MANIFEST_NAME = "manifest.json"
RELEASE_NAME = "release.json"
SBOM_NAME = "sbom.cdx.json"
SCAN_REPORT_NAME = "scan-report.json"
CHECKSUM_NAME = "SHA256SUMS"

PAYLOAD_NAMES = (
    SERVER_NAME,
    ADMIN_NAME,
    MANIFEST_NAME,
    RELEASE_NAME,
    SBOM_NAME,
    SCAN_REPORT_NAME,
    CHECKSUM_NAME,
)
ARTIFACT_NAMES = (
    SERVER_NAME,
    ADMIN_NAME,
    RELEASE_NAME,
    SBOM_NAME,
    SCAN_REPORT_NAME,
)
CHECKSUM_NAMES = tuple(name for name in PAYLOAD_NAMES if name != CHECKSUM_NAME)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_identity(tag, commit, run_id):
    if not TAG_PATTERN.fullmatch(tag):
        raise ValueError("tag must match vX.Y.Z-uat.N without leading zeroes")
    if not COMMIT_PATTERN.fullmatch(commit):
        raise ValueError("commit must be a full 40-character lowercase Git SHA")
    if not RUN_ID_PATTERN.fullmatch(run_id) or int(run_id) > MAX_RUN_ID:
        raise ValueError("run ID must be a positive decimal signed 64-bit integer")


def require_regular_file(path, label, maximum_bytes):
    try:
        details = os.lstat(str(path))
    except OSError as error:
        raise ValueError("{0} is unavailable: {1}".format(label, error))
    if stat.S_ISLNK(details.st_mode) or not stat.S_ISREG(details.st_mode):
        raise ValueError("{0} must be a regular non-symlink file".format(label))
    if details.st_size <= 0:
        raise ValueError("{0} must not be empty".format(label))
    if details.st_size > maximum_bytes:
        raise ValueError("{0} exceeds the {1}-byte limit".format(label, maximum_bytes))
    return details.st_size


def reject_json_constant(value):
    raise ValueError("JSON constants such as {0} are forbidden".format(value))


def unique_json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("JSON contains a duplicate key: {0}".format(key))
        result[key] = value
    return result


def load_json_bytes(content, label):
    try:
        value = json.loads(
            content.decode("utf-8"),
            object_pairs_hook=unique_json_object,
            parse_constant=reject_json_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("{0} is not valid UTF-8 JSON: {1}".format(label, error))
    if not isinstance(value, dict):
        raise ValueError("{0} must contain a JSON object".format(label))
    return value


def validate_json_file(path, label, maximum_bytes):
    require_regular_file(path, label, maximum_bytes)
    with path.open("rb") as stream:
        load_json_bytes(stream.read(maximum_bytes + 1), label)


def validate_archive_name(name, label, allow_nested):
    try:
        name.encode("ascii")
    except UnicodeEncodeError:
        raise ValueError("{0} contains a non-ASCII member name".format(label))
    if not name or len(name.encode("utf-8")) > MAX_MEMBER_NAME_BYTES:
        raise ValueError("{0} contains an empty or oversized member name".format(label))
    if "\\" in name or name.startswith("/") or any(ord(character) < 32 for character in name):
        raise ValueError("{0} contains an unsafe member name: {1}".format(label, name))
    normalized = name[:-1] if name.endswith("/") else name
    parts = normalized.split("/")
    if not normalized or any(part in ("", ".", "..") for part in parts):
        raise ValueError("{0} contains an unsafe member name: {1}".format(label, name))
    if not allow_nested and len(parts) != 1:
        raise ValueError("{0} member is not top-level: {1}".format(label, name))


def database_migrations_sha256(jar):
    require_regular_file(jar, "server JAR", MAX_JAR_BYTES)
    try:
        with zipfile.ZipFile(str(jar), "r") as archive:
            entries = archive.infolist()
            if not entries or len(entries) > MAX_JAR_MEMBERS:
                raise ValueError("server JAR member count exceeds the fixed limit")

            names = set()
            expanded_bytes = 0
            migrations = []
            for entry in entries:
                validate_archive_name(entry.filename, "server JAR", True)
                if entry.filename in names:
                    raise ValueError(
                        "server JAR contains a duplicate member: {0}".format(entry.filename)
                    )
                names.add(entry.filename)
                if entry.flag_bits & 0x1:
                    raise ValueError("server JAR contains an encrypted member")
                if entry.file_size < 0 or entry.file_size > MAX_JAR_ENTRY_BYTES:
                    raise ValueError("server JAR member exceeds the per-file limit")
                expanded_bytes += entry.file_size
                if expanded_bytes > MAX_JAR_EXPANDED_BYTES:
                    raise ValueError("server JAR expanded size exceeds the fixed limit")
                if (
                    not entry.filename.endswith("/")
                    and "/db/migration/" in "/" + entry.filename
                ):
                    if entry.file_size > MAX_MIGRATION_ENTRY_BYTES:
                        raise ValueError("database migration exceeds the per-file limit")
                    migrations.append(entry)

            if not migrations:
                raise ValueError("server JAR does not contain database migrations")
            if len(migrations) > MAX_MIGRATION_MEMBERS:
                raise ValueError("server JAR contains too many database migrations")
            if sum(entry.file_size for entry in migrations) > MAX_MIGRATION_EXPANDED_BYTES:
                raise ValueError("database migrations exceed the expanded-size limit")

            digest = hashlib.sha256()
            for entry in sorted(migrations, key=lambda item: item.filename):
                member_digest = hashlib.sha256()
                actual_bytes = 0
                with archive.open(entry, "r") as stream:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        actual_bytes += len(chunk)
                        if actual_bytes > MAX_MIGRATION_ENTRY_BYTES:
                            raise ValueError("database migration exceeds the per-file limit")
                        member_digest.update(chunk)
                if actual_bytes != entry.file_size:
                    raise ValueError(
                        "database migration size differs from its JAR metadata"
                    )
                digest.update(entry.filename.encode("utf-8"))
                digest.update(b"\0")
                digest.update(member_digest.digest())
            return digest.hexdigest()
    except (zipfile.BadZipFile, RuntimeError, OSError) as error:
        raise ValueError(
            "server JAR is not a valid readable ZIP archive: {0}".format(error)
        )


def collect_admin_members(root):
    try:
        root_details = os.lstat(str(root))
    except OSError as error:
        raise ValueError("admin build is unavailable: {0}".format(error))
    if stat.S_ISLNK(root_details.st_mode) or not stat.S_ISDIR(root_details.st_mode):
        raise ValueError("admin build must be a real non-symlink directory")

    members = [(root, "admin", True, 0)]
    expanded_bytes = 0
    pending = [(root, "admin")]
    while pending:
        directory, archive_directory = pending.pop()
        try:
            entries = sorted(os.scandir(str(directory)), key=lambda item: item.name)
        except OSError as error:
            raise ValueError("admin build directory cannot be read: {0}".format(error))
        child_directories = []
        for entry in entries:
            candidate = Path(entry.path)
            archive_name = archive_directory + "/" + entry.name
            validate_archive_name(archive_name, "admin build", True)
            details = entry.stat(follow_symlinks=False)
            if entry.is_symlink():
                raise ValueError(
                    "admin build contains a symlink: {0}".format(archive_name)
                )
            if stat.S_ISDIR(details.st_mode):
                members.append((candidate, archive_name, True, 0))
                child_directories.append((candidate, archive_name))
            elif stat.S_ISREG(details.st_mode):
                if details.st_size > MAX_ADMIN_FILE_BYTES:
                    raise ValueError(
                        "admin file exceeds the per-file limit: {0}".format(
                            archive_name
                        )
                    )
                expanded_bytes += details.st_size
                if expanded_bytes > MAX_ADMIN_EXPANDED_BYTES:
                    raise ValueError("admin build exceeds the expanded-size limit")
                members.append((candidate, archive_name, False, details.st_size))
            else:
                raise ValueError(
                    "admin build contains a special file: {0}".format(archive_name)
                )
            if len(members) > MAX_ADMIN_MEMBERS:
                raise ValueError("admin build contains too many members")
        pending.extend(reversed(child_directories))

    members.sort(key=lambda item: item[1])
    index_matches = [
        item for item in members if item[1] == "admin/index.html" and not item[2]
    ]
    if len(index_matches) != 1:
        raise ValueError("admin build must contain one regular top-level index.html")
    return members


def deterministic_tar_info(name, size, is_directory):
    info = tarfile.TarInfo(name)
    info.uid = 0
    info.gid = 0
    info.uname = "root"
    info.gname = "root"
    info.mtime = 0
    info.mode = 0o755 if is_directory else 0o644
    if is_directory:
        info.type = tarfile.DIRTYPE
        info.size = 0
    else:
        info.type = tarfile.REGTYPE
        info.size = size
    return info


def open_deterministic_tar(path):
    raw_stream = path.open("wb")
    gzip_stream = gzip.GzipFile(filename="", mode="wb", fileobj=raw_stream, mtime=0)
    archive = tarfile.open(
        fileobj=gzip_stream, mode="w", format=tarfile.PAX_FORMAT
    )
    return raw_stream, gzip_stream, archive


def create_admin_archive(admin_dir, destination):
    members = collect_admin_members(admin_dir)
    raw_stream, gzip_stream, archive = open_deterministic_tar(destination)
    try:
        for path, archive_name, is_directory, size in members:
            info = deterministic_tar_info(archive_name, size, is_directory)
            if is_directory:
                archive.addfile(info)
            else:
                with path.open("rb") as source:
                    archive.addfile(info, source)
    finally:
        archive.close()
        gzip_stream.close()
        raw_stream.close()
    require_regular_file(destination, "admin archive", MAX_ADMIN_ARCHIVE_BYTES)
    validate_admin_archive(destination)


def validate_admin_archive(archive_path):
    require_regular_file(archive_path, "admin archive", MAX_ADMIN_ARCHIVE_BYTES)
    names = set()
    member_count = 0
    expanded_bytes = 0
    index_found = False
    try:
        with tarfile.open(str(archive_path), "r|gz") as archive:
            for member in archive:
                member_count += 1
                if member_count > MAX_ADMIN_MEMBERS:
                    raise ValueError("admin archive contains too many members")
                validate_archive_name(member.name, "admin archive", True)
                if set(member.pax_headers) - {"path"} or (
                    "path" in member.pax_headers
                    and member.pax_headers["path"] != member.name
                ):
                    raise ValueError("admin archive contains unsupported extended metadata")
                if member.name in names:
                    raise ValueError("admin archive contains a duplicate member")
                names.add(member.name)
                if member.name != "admin" and not member.name.startswith("admin/"):
                    raise ValueError("admin archive member escapes its fixed root")
                if not (member.isdir() or member.isfile()):
                    raise ValueError("admin archive contains a link or special file")
                if member.isfile():
                    if member.size < 0 or member.size > MAX_ADMIN_FILE_BYTES:
                        raise ValueError(
                            "admin archive member exceeds the per-file limit"
                        )
                    expanded_bytes += member.size
                    if expanded_bytes > MAX_ADMIN_EXPANDED_BYTES:
                        raise ValueError(
                            "admin archive exceeds the expanded-size limit"
                        )
                    stream = archive.extractfile(member)
                    if stream is None:
                        raise ValueError("admin archive file cannot be read")
                    actual_bytes = 0
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        actual_bytes += len(chunk)
                    if actual_bytes != member.size:
                        raise ValueError(
                            "admin archive member size differs from its metadata"
                        )
                    if member.name == "admin/index.html":
                        index_found = True
    except (tarfile.TarError, EOFError, OSError) as error:
        raise ValueError("admin archive is invalid: {0}".format(error))
    if member_count < 2 or not index_found:
        raise ValueError("admin archive is missing admin/index.html")


def write_json(path, value):
    content = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    encoded = content.encode("utf-8")
    if len(encoded) > MAX_METADATA_BYTES:
        raise ValueError("metadata exceeds the fixed size limit")
    with path.open("wb") as stream:
        stream.write(encoded)


def write_checksums(staging):
    checksum_path = staging / CHECKSUM_NAME
    content = "".join(
        "{0}  {1}\n".format(sha256(staging / name), name)
        for name in sorted(CHECKSUM_NAMES)
    ).encode("ascii")
    if len(content) > MAX_METADATA_BYTES:
        raise ValueError("SHA256SUMS exceeds the fixed metadata limit")
    with checksum_path.open("wb") as stream:
        stream.write(content)


def payload_limit(name):
    if name == SERVER_NAME:
        return MAX_JAR_BYTES
    if name == ADMIN_NAME:
        return MAX_ADMIN_ARCHIVE_BYTES
    if name == SBOM_NAME:
        return MAX_SBOM_BYTES
    if name == SCAN_REPORT_NAME:
        return MAX_SCAN_REPORT_BYTES
    return MAX_METADATA_BYTES


def create_bundle(staging, bundle_path):
    present = sorted(path.name for path in staging.iterdir())
    if present != sorted(PAYLOAD_NAMES):
        raise ValueError("bundle staging does not contain the exact payload set")
    expanded_bytes = 0
    for name in PAYLOAD_NAMES:
        expanded_bytes += require_regular_file(
            staging / name, name, payload_limit(name)
        )
    if expanded_bytes > MAX_BUNDLE_EXPANDED_BYTES:
        raise ValueError("bundle payload exceeds the expanded-size limit")

    raw_stream, gzip_stream, archive = open_deterministic_tar(bundle_path)
    try:
        for name in sorted(PAYLOAD_NAMES):
            source = staging / name
            info = deterministic_tar_info(name, source.stat().st_size, False)
            with source.open("rb") as stream:
                archive.addfile(info, stream)
    finally:
        archive.close()
        gzip_stream.close()
        raw_stream.close()
    require_regular_file(bundle_path, "UAT bundle", MAX_BUNDLE_COMPRESSED_BYTES)


def validate_exact_identity(
    document, expected_keys, tag, commit, run_id, migration_digest, label
):
    if set(document) != set(expected_keys):
        raise ValueError("{0} fields do not match the fixed schema".format(label))
    expected = {
        "schemaVersion": 1,
        "environment": "uat",
        "tag": tag,
        "commit": commit,
        "runId": run_id,
        "databaseMigrationsSha256": migration_digest,
    }
    for key, value in expected.items():
        if document.get(key) != value or (
            key == "schemaVersion" and type(document.get(key)) is not int
        ):
            raise ValueError("{0} identity differs for {1}".format(label, key))


def parse_checksums(content):
    try:
        text = content.decode("ascii")
    except UnicodeDecodeError as error:
        raise ValueError("SHA256SUMS must be ASCII: {0}".format(error))
    if not text.endswith("\n"):
        raise ValueError("SHA256SUMS must end with a newline")
    checksums = {}
    for line in text.splitlines():
        match = CHECKSUM_LINE_PATTERN.fullmatch(line)
        if match is None or match.group(2) in checksums:
            raise ValueError("SHA256SUMS contains an invalid or duplicate line")
        checksums[match.group(2)] = match.group(1)
    if set(checksums) != set(CHECKSUM_NAMES):
        raise ValueError("SHA256SUMS does not cover the exact payload set")
    return checksums


def validate_bundle(bundle_path, tag, commit, run_id):
    validate_identity(tag, commit, run_id)
    expected_name = "joysong-uat-{0}.tar.gz".format(tag)
    if bundle_path.name != expected_name:
        raise ValueError("bundle filename does not match its immutable tag")
    require_regular_file(bundle_path, "UAT bundle", MAX_BUNDLE_COMPRESSED_BYTES)

    digests = {}
    sizes = {}
    metadata = {}
    member_count = 0
    expanded_bytes = 0
    with tempfile.TemporaryDirectory(
        prefix="joysong-bundle-verify-"
    ) as temporary:
        jar_copy = Path(temporary) / SERVER_NAME
        try:
            with tarfile.open(str(bundle_path), "r|gz") as archive:
                for member in archive:
                    member_count += 1
                    if member_count > MAX_BUNDLE_MEMBERS:
                        raise ValueError("UAT bundle contains too many members")
                    validate_archive_name(member.name, "UAT bundle", False)
                    if member.pax_headers:
                        raise ValueError("UAT bundle contains extended metadata")
                    if member.name not in PAYLOAD_NAMES or member.name in digests:
                        raise ValueError(
                            "UAT bundle contains an unexpected or duplicate member"
                        )
                    if not member.isfile():
                        raise ValueError(
                            "UAT bundle members must all be regular files"
                        )
                    if (
                        member.size <= 0
                        or member.size > payload_limit(member.name)
                    ):
                        raise ValueError(
                            "UAT bundle member exceeds its fixed size limit"
                        )
                    expanded_bytes += member.size
                    if expanded_bytes > MAX_BUNDLE_EXPANDED_BYTES:
                        raise ValueError(
                            "UAT bundle exceeds the expanded-size limit"
                        )
                    source = archive.extractfile(member)
                    if source is None:
                        raise ValueError("UAT bundle member cannot be read")
                    digest = hashlib.sha256()
                    actual_bytes = 0
                    captured = bytearray()
                    jar_stream = (
                        jar_copy.open("wb") if member.name == SERVER_NAME else None
                    )
                    try:
                        for chunk in iter(
                            lambda: source.read(1024 * 1024), b""
                        ):
                            actual_bytes += len(chunk)
                            if actual_bytes > payload_limit(member.name):
                                raise ValueError(
                                    "UAT bundle member exceeds its fixed size limit"
                                )
                            digest.update(chunk)
                            if member.name in (
                                MANIFEST_NAME,
                                RELEASE_NAME,
                                CHECKSUM_NAME,
                            ):
                                captured.extend(chunk)
                            if jar_stream is not None:
                                jar_stream.write(chunk)
                    finally:
                        if jar_stream is not None:
                            jar_stream.close()
                    if actual_bytes != member.size:
                        raise ValueError(
                            "UAT bundle member size differs from its metadata"
                        )
                    digests[member.name] = digest.hexdigest()
                    sizes[member.name] = actual_bytes
                    if captured:
                        metadata[member.name] = bytes(captured)
        except (tarfile.TarError, EOFError, OSError) as error:
            raise ValueError("UAT bundle is invalid: {0}".format(error))

        if member_count != MAX_BUNDLE_MEMBERS or set(digests) != set(PAYLOAD_NAMES):
            raise ValueError("UAT bundle does not contain the exact payload set")
        checksums = parse_checksums(metadata[CHECKSUM_NAME])
        for name, expected_digest in checksums.items():
            if digests.get(name) != expected_digest:
                raise ValueError("SHA256SUMS mismatch for {0}".format(name))

        migration_digest = database_migrations_sha256(jar_copy)
        release = load_json_bytes(metadata[RELEASE_NAME], RELEASE_NAME)
        manifest = load_json_bytes(metadata[MANIFEST_NAME], MANIFEST_NAME)
        release_keys = (
            "schemaVersion",
            "environment",
            "tag",
            "commit",
            "runId",
            "databaseMigrationsSha256",
            "status",
        )
        manifest_keys = (
            "schemaVersion",
            "environment",
            "tag",
            "commit",
            "runId",
            "databaseMigrationsSha256",
            "artifacts",
        )
        validate_exact_identity(
            release,
            release_keys,
            tag,
            commit,
            run_id,
            migration_digest,
            RELEASE_NAME,
        )
        validate_exact_identity(
            manifest,
            manifest_keys,
            tag,
            commit,
            run_id,
            migration_digest,
            MANIFEST_NAME,
        )
        if release.get("status") != "UAT Candidate":
            raise ValueError(
                "release status is not the fixed UAT Candidate value"
            )
        artifacts = manifest.get("artifacts")
        if not isinstance(artifacts, dict) or set(artifacts) != set(
            ARTIFACT_NAMES
        ):
            raise ValueError(
                "manifest artifacts do not match the fixed artifact set"
            )
        for name in ARTIFACT_NAMES:
            details = artifacts.get(name)
            if not isinstance(details, dict) or set(details) != {
                "sha256",
                "size",
            }:
                raise ValueError(
                    "manifest artifact fields are invalid for {0}".format(name)
                )
            artifact_digest = details.get("sha256", "")
            if (
                not isinstance(artifact_digest, str)
                or not SHA256_PATTERN.fullmatch(artifact_digest)
                or artifact_digest != digests[name]
            ):
                raise ValueError(
                    "manifest artifact hash differs for {0}".format(name)
                )
            if (
                type(details.get("size")) is not int
                or details.get("size") != sizes[name]
            ):
                raise ValueError(
                    "manifest artifact size differs for {0}".format(name)
                )
        return {
            "bundleSha256": sha256(bundle_path),
            "databaseMigrationsSha256": migration_digest,
        }


def prepare_output_directory(output):
    if os.path.lexists(str(output)):
        details = os.lstat(str(output))
        if stat.S_ISLNK(details.st_mode) or not stat.S_ISDIR(details.st_mode):
            raise ValueError("output must be a real non-symlink directory")
        if any(output.iterdir()):
            raise ValueError("output directory must be empty")
    else:
        output.mkdir(parents=True)
    return output.resolve()


def build_release(
    output, tag, commit, run_id, jar, admin_dir, sbom, scan_report
):
    validate_identity(tag, commit, run_id)
    require_regular_file(jar, "server JAR", MAX_JAR_BYTES)
    validate_json_file(sbom, "SBOM", MAX_SBOM_BYTES)
    validate_json_file(scan_report, "scan report", MAX_SCAN_REPORT_BYTES)
    migration_digest = database_migrations_sha256(jar)
    output = prepare_output_directory(output)
    bundle_name = "joysong-uat-{0}.tar.gz".format(tag)
    sidecar_name = bundle_name + ".sha256"

    with tempfile.TemporaryDirectory(
        prefix=".package-", dir=str(output)
    ) as temporary:
        temporary_root = Path(temporary)
        staging = temporary_root / "payload"
        staging.mkdir()
        shutil.copyfile(str(jar), str(staging / SERVER_NAME))
        shutil.copyfile(str(sbom), str(staging / SBOM_NAME))
        shutil.copyfile(str(scan_report), str(staging / SCAN_REPORT_NAME))
        create_admin_archive(admin_dir, staging / ADMIN_NAME)

        release = {
            "schemaVersion": 1,
            "environment": "uat",
            "tag": tag,
            "commit": commit,
            "runId": run_id,
            "databaseMigrationsSha256": migration_digest,
            "status": "UAT Candidate",
        }
        write_json(staging / RELEASE_NAME, release)
        artifacts = {}
        for name in ARTIFACT_NAMES:
            path = staging / name
            artifacts[name] = {
                "sha256": sha256(path),
                "size": path.stat().st_size,
            }
        manifest = {
            "schemaVersion": 1,
            "environment": "uat",
            "tag": tag,
            "commit": commit,
            "runId": run_id,
            "databaseMigrationsSha256": migration_digest,
            "artifacts": artifacts,
        }
        write_json(staging / MANIFEST_NAME, manifest)
        write_checksums(staging)

        temporary_bundle = temporary_root / bundle_name
        create_bundle(staging, temporary_bundle)
        verification = validate_bundle(
            temporary_bundle, tag, commit, run_id
        )
        bundle_digest = verification["bundleSha256"]
        temporary_sidecar = temporary_root / sidecar_name
        with temporary_sidecar.open(
            "w", encoding="ascii", newline="\n"
        ) as stream:
            stream.write(
                "{0}  {1}\n".format(bundle_digest, bundle_name)
            )

        final_bundle = output / bundle_name
        final_sidecar = output / sidecar_name
        if os.path.lexists(str(final_bundle)) or os.path.lexists(
            str(final_sidecar)
        ):
            raise ValueError("immutable bundle output already exists")
        os.replace(str(temporary_bundle), str(final_bundle))
        os.replace(str(temporary_sidecar), str(final_sidecar))

    return final_bundle, bundle_digest, final_sidecar


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--admin-dir", type=Path, required=True)
    parser.add_argument("--sbom", type=Path, required=True)
    parser.add_argument("--scan-report", type=Path, required=True)
    args = parser.parse_args()

    bundle, bundle_digest, sidecar = build_release(
        args.output,
        args.tag,
        args.commit,
        args.run_id,
        args.jar,
        args.admin_dir,
        args.sbom,
        args.scan_report,
    )
    print("BUNDLE_PATH={0}".format(bundle))
    print("BUNDLE_SHA256={0}".format(bundle_digest))
    print("BUNDLE_SHA256_FILE={0}".format(sidecar))


if __name__ == "__main__":
    main()
