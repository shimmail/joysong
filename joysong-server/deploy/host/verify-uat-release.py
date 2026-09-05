#!/usr/bin/env python3
"""Validate and safely expand one immutable JoySong UAT deployment bundle."""

from __future__ import print_function

import hashlib
import json
import os
import re
import shutil
import stat
import struct
import sys
import tarfile
import zipfile


MAX_BUNDLE_BYTES = 1024 * 1024 * 1024
MAX_OUTER_EXPANDED_BYTES = 2 * 1024 * 1024 * 1024
MAX_BUNDLE_MEMBERS = 7
MAX_JAR_BYTES = 512 * 1024 * 1024
MAX_JAR_EXPANDED_BYTES = 2 * 1024 * 1024 * 1024
MAX_JAR_MEMBERS = 50000
MAX_JAR_MEMBER_BYTES = 512 * 1024 * 1024
MAX_ADMIN_BYTES = 512 * 1024 * 1024
MAX_ADMIN_EXPANDED_BYTES = 1024 * 1024 * 1024
MAX_ADMIN_MEMBERS = 10000
MAX_ADMIN_MEMBER_BYTES = 128 * 1024 * 1024
MAX_METADATA_BYTES = 64 * 1024 * 1024
MAX_SMALL_METADATA_BYTES = 1024 * 1024
MAX_MIGRATION_MEMBERS = 512
MAX_MIGRATION_BYTES = 32 * 1024 * 1024
MAX_MIGRATION_MEMBER_BYTES = 2 * 1024 * 1024
MAX_MEMBER_NAME_BYTES = 240
VERIFICATION_RESERVE_BYTES = 512 * 1024 * 1024

EXPECTED_MEMBERS = {
    "joysong-server.jar",
    "joysong-admin.tar.gz",
    "manifest.json",
    "release.json",
    "sbom.cdx.json",
    "scan-report.json",
    "SHA256SUMS",
}
ARTIFACT_NAMES = EXPECTED_MEMBERS - {"manifest.json", "SHA256SUMS"}
CHECKSUM_NAMES = EXPECTED_MEMBERS - {"SHA256SUMS"}
TAG_RE = re.compile(
    r"^v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\."
    r"(?:0|[1-9][0-9]*)-uat\.(?:0|[1-9][0-9]*)$"
)
LOWER_SHA_RE = re.compile(r"^[0-9a-f]{64}$")
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")
RUN_ID_RE = re.compile(r"^[1-9][0-9]{0,18}$")
RUN_ID_MAX = 9223372036854775807


class ContractError(Exception):
    pass


def fail(message):
    raise ContractError(message)


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def freeze_bundle(source, destination, expected_sha):
    if not os.path.isabs(source) or not os.path.isabs(destination):
        fail("bundle freeze paths must be absolute")
    source_directory = os.path.dirname(source)
    source_name = os.path.basename(source)
    if (
        not source_name
        or source != os.path.join(source_directory, source_name)
        or not hasattr(os, "O_DIRECTORY")
        or not hasattr(os, "O_NOFOLLOW")
        or os.open not in os.supports_dir_fd
    ):
        fail("bundle freeze requires one canonical Linux path with openat support")
    if not LOWER_SHA_RE.fullmatch(expected_sha):
        fail("invalid frozen bundle SHA-256")
    capacity = os.statvfs(os.path.dirname(destination))
    available = capacity.f_bavail * capacity.f_frsize
    required = (
        MAX_BUNDLE_BYTES
        + MAX_OUTER_EXPANDED_BYTES
        + MAX_ADMIN_EXPANDED_BYTES
        + VERIFICATION_RESERVE_BYTES
    )
    if available < required:
        fail("insufficient state filesystem capacity for bounded release verification")
    directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    source_flags = os.O_RDONLY | os.O_NOFOLLOW
    if hasattr(os, "O_NONBLOCK"):
        source_flags |= os.O_NONBLOCK
    if hasattr(os, "O_CLOEXEC"):
        directory_flags |= os.O_CLOEXEC
        source_flags |= os.O_CLOEXEC
    destination_flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    destination_flags |= os.O_NOFOLLOW
    directory_details = os.lstat(source_directory)
    if (
        not stat.S_ISDIR(directory_details.st_mode)
        or stat.S_IMODE(directory_details.st_mode) != 0o700
    ):
        fail("deployment bundle directory is not a real mode 0700 directory")
    try:
        directory_fd = os.open(source_directory, directory_flags)
    except OSError as error:
        fail("deployment bundle directory cannot be opened safely: {}".format(error))
    source_fd = None
    destination_fd = None
    digest = hashlib.sha256()
    copied = 0
    try:
        opened_directory = os.fstat(directory_fd)
        if (
            opened_directory.st_dev != directory_details.st_dev
            or opened_directory.st_ino != directory_details.st_ino
            or opened_directory.st_uid != directory_details.st_uid
            or opened_directory.st_gid != directory_details.st_gid
            or stat.S_IMODE(opened_directory.st_mode) != 0o700
        ):
            fail("deployment bundle directory changed before it was anchored")
        try:
            source_fd = os.open(source_name, source_flags, dir_fd=directory_fd)
        except OSError as error:
            fail("deployment bundle cannot be opened safely: {}".format(error))
        details = os.fstat(source_fd)
        if (
            not stat.S_ISREG(details.st_mode)
            or details.st_nlink != 1
            or details.st_uid != opened_directory.st_uid
            or details.st_gid != opened_directory.st_gid
            or stat.S_IMODE(details.st_mode) & 0o022
            or details.st_size <= 0
            or details.st_size > MAX_BUNDLE_BYTES
        ):
            fail("deployment bundle source is not a bounded single regular file")
        destination_fd = os.open(destination, destination_flags, 0o400)
        while True:
            chunk = os.read(source_fd, min(1024 * 1024, MAX_BUNDLE_BYTES + 1 - copied))
            if not chunk:
                break
            copied += len(chunk)
            if copied > MAX_BUNDLE_BYTES:
                fail("deployment bundle grew beyond the compressed-size limit")
            digest.update(chunk)
            offset = 0
            while offset < len(chunk):
                offset += os.write(destination_fd, chunk[offset:])
        if copied != details.st_size:
            fail("deployment bundle size changed while it was frozen")
        os.fsync(destination_fd)
    finally:
        if source_fd is not None:
            os.close(source_fd)
        os.close(directory_fd)
        if destination_fd is not None:
            os.close(destination_fd)
    actual_sha = digest.hexdigest()
    if actual_sha != expected_sha:
        fail("frozen candidate differs from the hosted Artifact SHA-256")
    print("BUNDLE_SHA256={}".format(actual_sha))


def strict_json(path, label, maximum):
    if os.path.getsize(path) > maximum:
        fail("{} exceeds its metadata size limit".format(label))

    def no_duplicates(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                fail("{} contains a duplicate JSON key".format(label))
            result[key] = value
        return result

    try:
        with open(path, "r", encoding="utf-8") as stream:
            value = json.load(stream, object_pairs_hook=no_duplicates)
    except (OSError, UnicodeError, ValueError) as error:
        fail("{} is invalid JSON: {}".format(label, error))
    if not isinstance(value, dict):
        fail("{} must be a JSON object".format(label))
    return value


def safe_member_name(name, allow_nested):
    try:
        name.encode("ascii")
    except UnicodeEncodeError:
        fail("archive member name must be ASCII on the UAT host")
    if (
        not name
        or len(name.encode("utf-8")) > MAX_MEMBER_NAME_BYTES
        or "\\" in name
        or "\x00" in name
        or any(ord(character) < 32 for character in name)
    ):
        fail("archive member has an invalid name")
    if name.startswith("/") or name.startswith("./") or "//" in name:
        fail("archive member path is not canonical")
    parts = name.rstrip("/").split("/")
    if any(part in ("", ".", "..") for part in parts):
        fail("archive member escapes its root")
    if not allow_nested and len(parts) != 1:
        fail("outer archive members must be top-level files")
    return "/".join(parts)


def verify_outer_and_extract(bundle, staging):
    bundle_stat = os.lstat(bundle)
    if not stat.S_ISREG(bundle_stat.st_mode) or bundle_stat.st_nlink != 1:
        fail("deployment bundle must be a single regular non-link file")
    if bundle_stat.st_size <= 0 or bundle_stat.st_size > MAX_BUNDLE_BYTES:
        fail("deployment bundle exceeds the compressed-size limit")
    if os.path.lexists(staging):
        if os.path.islink(staging) or not os.path.isdir(staging) or os.listdir(staging):
            fail("staging directory must be a real empty directory")
    else:
        os.makedirs(staging, 0o700)

    try:
        archive = tarfile.open(bundle, "r|gz")
    except (OSError, tarfile.TarError) as error:
        fail("deployment bundle is not a valid gzip tar: {}".format(error))
    with archive:
        names = []
        expanded = 0
        for member in archive:
            if len(names) >= MAX_BUNDLE_MEMBERS:
                fail("deployment bundle has too many members")
            name = safe_member_name(member.name, False)
            if not member.isfile() or member.issym() or member.islnk() or member.pax_headers:
                fail("deployment bundle contains a link, special file, directory, or extended metadata")
            if name in names:
                fail("deployment bundle contains a duplicate member")
            names.append(name)
            expanded += member.size
            maximum = MAX_METADATA_BYTES
            if name in ("manifest.json", "release.json", "SHA256SUMS"):
                maximum = MAX_SMALL_METADATA_BYTES
            elif name == "joysong-server.jar":
                maximum = MAX_JAR_BYTES
            elif name == "joysong-admin.tar.gz":
                maximum = MAX_ADMIN_BYTES
            if member.size < 0 or member.size > maximum:
                fail("deployment bundle member exceeds its size limit: {}".format(name))
            if expanded > MAX_OUTER_EXPANDED_BYTES:
                fail("deployment bundle exceeds the expanded-size limit")
            destination = os.path.join(staging, name)
            source = archive.extractfile(member)
            if source is None:
                fail("deployment bundle member cannot be read")
            try:
                with open(destination, "xb") as output:
                    shutil.copyfileobj(source, output, 1024 * 1024)
                os.chmod(destination, 0o600)
            finally:
                source.close()
        if len(names) != MAX_BUNDLE_MEMBERS or set(names) != EXPECTED_MEMBERS:
            fail("deployment bundle member set is invalid")


def validate_zip_directory(path):
    """Bound the classic ZIP directory before zipfile parses attacker metadata."""
    details = os.stat(path)
    maximum_tail = 22 + 65535
    tail_size = min(details.st_size, maximum_tail)
    if tail_size < 22:
        fail("server JAR is missing its ZIP end record")
    with open(path, "rb") as stream:
        stream.seek(details.st_size - tail_size)
        tail = stream.read(tail_size)
    signature = b"PK\x05\x06"
    relative_eocd = tail.rfind(signature)
    if relative_eocd < 0 or len(tail) - relative_eocd < 22:
        fail("server JAR is missing its ZIP end record")
    eocd = tail[relative_eocd : relative_eocd + 22]
    (
        _signature,
        disk_number,
        directory_disk,
        disk_entries,
        total_entries,
        directory_size,
        directory_offset,
        comment_size,
    ) = struct.unpack("<4s4H2LH", eocd)
    if relative_eocd + 22 + comment_size != len(tail):
        fail("server JAR ZIP end record is not canonical")
    if disk_number != 0 or directory_disk != 0 or disk_entries != total_entries:
        fail("server JAR must be one single-disk ZIP")
    if (
        total_entries in (0, 0xFFFF)
        or total_entries > MAX_JAR_MEMBERS
        or directory_size == 0xFFFFFFFF
        or directory_offset == 0xFFFFFFFF
    ):
        fail("server JAR ZIP64 or member count is unsupported")
    eocd_offset = details.st_size - tail_size + relative_eocd
    if directory_offset + directory_size != eocd_offset:
        fail("server JAR central directory bounds are invalid")
    if directory_size > details.st_size or directory_offset >= eocd_offset:
        fail("server JAR central directory is invalid")
    if relative_eocd >= 20 and tail[relative_eocd - 20 : relative_eocd - 16] == b"PK\x06\x07":
        fail("server JAR ZIP64 is unsupported")
    with open(path, "rb") as stream:
        stream.seek(directory_offset)
        if stream.read(4) != b"PK\x01\x02":
            fail("server JAR central directory signature is invalid")
    return total_entries


def validate_jar_and_migrations(path):
    if os.path.getsize(path) > MAX_JAR_BYTES:
        fail("server JAR exceeds the compressed-size limit")
    expected_member_count = validate_zip_directory(path)
    digest = hashlib.sha256()
    try:
        jar = zipfile.ZipFile(path, "r")
    except (OSError, zipfile.BadZipFile) as error:
        fail("server JAR is invalid: {}".format(error))
    with jar:
        members = jar.infolist()
        if not members or len(members) != expected_member_count or len(members) > MAX_JAR_MEMBERS:
            fail("server JAR member count is invalid")
        names = []
        expanded = 0
        migration_members = []
        migration_bytes = 0
        for member in members:
            name = safe_member_name(member.filename, True)
            names.append(name)
            mode = (member.external_attr >> 16) & 0o170000
            if mode == stat.S_IFLNK:
                fail("server JAR contains a symbolic link")
            if member.file_size < 0 or member.file_size > MAX_JAR_MEMBER_BYTES:
                fail("server JAR member exceeds its size limit")
            expanded += member.file_size
            if "/db/migration/" in "/" + name and not name.endswith("/"):
                if member.file_size > MAX_MIGRATION_MEMBER_BYTES:
                    fail("migration member exceeds its size limit")
                migration_members.append(member)
                migration_bytes += member.file_size
        if len(set(names)) != len(names):
            fail("server JAR contains duplicate members")
        if expanded > MAX_JAR_EXPANDED_BYTES:
            fail("server JAR exceeds the expanded-size limit")
        if not migration_members or len(migration_members) > MAX_MIGRATION_MEMBERS:
            fail("server JAR migration member count is invalid")
        if migration_bytes > MAX_MIGRATION_BYTES:
            fail("server JAR migrations exceed the expanded-size limit")
        for member in sorted(migration_members, key=lambda item: item.filename):
            digest.update(member.filename.encode("utf-8"))
            digest.update(b"\0")
            member_digest = hashlib.sha256()
            with jar.open(member, "r") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    member_digest.update(chunk)
            digest.update(member_digest.digest())
    return digest.hexdigest()


def verify_admin_and_extract(path, staging):
    if os.path.getsize(path) > MAX_ADMIN_BYTES:
        fail("Admin archive exceeds the compressed-size limit")
    admin_destination = os.path.join(staging, "admin")
    if os.path.lexists(admin_destination):
        fail("Admin extraction destination already exists")
    os.makedirs(admin_destination, 0o755)
    try:
        archive = tarfile.open(path, "r|gz")
    except (OSError, tarfile.TarError) as error:
        fail("Admin archive is invalid: {}".format(error))
    with archive:
        names = []
        expanded = 0
        for member in archive:
            if len(names) >= MAX_ADMIN_MEMBERS:
                fail("Admin archive has too many members")
            name = safe_member_name(member.name, True)
            if name != "admin" and not name.startswith("admin/"):
                fail("Admin archive member escapes the admin root")
            pax_headers = member.pax_headers
            if set(pax_headers) - {"path"} or (
                "path" in pax_headers and pax_headers["path"] != member.name
            ):
                fail("Admin archive contains unsupported extended metadata")
            if member.issym() or member.islnk() or member.isdev() or member.isfifo():
                fail("Admin archive contains a link, special file, or extended metadata")
            if not (member.isdir() or member.isfile()):
                fail("Admin archive contains an unsupported member type")
            if member.isfile() and member.size > MAX_ADMIN_MEMBER_BYTES:
                fail("Admin archive member exceeds its size limit")
            canonical_name = name.rstrip("/")
            if canonical_name in names:
                fail("Admin archive contains duplicate members")
            names.append(canonical_name)
            expanded += member.size
            if expanded > MAX_ADMIN_EXPANDED_BYTES:
                fail("Admin archive exceeds the expanded-size limit")
            if canonical_name == "admin":
                continue
            relative = canonical_name[len("admin/") :]
            destination = os.path.join(admin_destination, *relative.split("/"))
            parent = os.path.dirname(destination)
            if not os.path.isdir(parent):
                os.makedirs(parent, 0o755)
            if member.isdir():
                if os.path.lexists(destination) and not os.path.isdir(destination):
                    fail("Admin extraction path collides with a file")
                if not os.path.exists(destination):
                    os.mkdir(destination, 0o755)
                continue
            source = archive.extractfile(member)
            if source is None:
                fail("Admin archive member cannot be read")
            try:
                with open(destination, "xb") as output:
                    shutil.copyfileobj(source, output, 1024 * 1024)
                os.chmod(destination, 0o644)
            finally:
                source.close()
        if not names or "admin/index.html" not in names:
            fail("Admin archive is empty or missing admin/index.html")


def parse_checksums(path):
    checksums = {}
    try:
        with open(path, "r", encoding="ascii") as stream:
            lines = stream.read().splitlines()
    except (OSError, UnicodeError) as error:
        fail("SHA256SUMS is unreadable: {}".format(error))
    if len(lines) != len(CHECKSUM_NAMES):
        fail("SHA256SUMS has an unexpected entry count")
    pattern = re.compile(r"^([0-9a-f]{64})  ([A-Za-z0-9][A-Za-z0-9._-]*)$")
    for line in lines:
        match = pattern.fullmatch(line)
        if not match or match.group(2) in checksums:
            fail("SHA256SUMS contains malformed or duplicate entries")
        checksums[match.group(2)] = match.group(1)
    if set(checksums) != CHECKSUM_NAMES:
        fail("SHA256SUMS does not cover the exact payload")
    return checksums


def validate_identity(staging, tag, commit, run_id):
    manifest_path = os.path.join(staging, "manifest.json")
    release_path = os.path.join(staging, "release.json")
    manifest = strict_json(manifest_path, "manifest.json", MAX_SMALL_METADATA_BYTES)
    release = strict_json(release_path, "release.json", MAX_SMALL_METADATA_BYTES)
    manifest_keys = {
        "schemaVersion", "environment", "tag", "commit", "runId",
        "databaseMigrationsSha256", "artifacts",
    }
    release_keys = {
        "schemaVersion", "environment", "tag", "commit", "runId",
        "databaseMigrationsSha256", "status",
    }
    if set(manifest) != manifest_keys or set(release) != release_keys:
        fail("release identity contains missing or unsupported fields")
    expected = {
        "schemaVersion": 1,
        "environment": "uat",
        "tag": tag,
        "commit": commit,
        "runId": run_id,
    }
    for key, value in expected.items():
        if manifest.get(key) != value or release.get(key) != value:
            fail("release identity mismatch: {}".format(key))
    if release.get("status") != "UAT Candidate":
        fail("release status is invalid")
    migration_sha = manifest.get("databaseMigrationsSha256")
    if not isinstance(migration_sha, str) or not LOWER_SHA_RE.fullmatch(migration_sha):
        fail("release migration digest is invalid")
    if release.get("databaseMigrationsSha256") != migration_sha:
        fail("manifest and release migration digests differ")
    actual_migration_sha = validate_jar_and_migrations(os.path.join(staging, "joysong-server.jar"))
    if actual_migration_sha != migration_sha:
        fail("server JAR migration digest differs from manifest")

    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, dict) or set(artifacts) != ARTIFACT_NAMES:
        fail("manifest artifacts do not match the fixed payload")
    for name in sorted(ARTIFACT_NAMES):
        identity = artifacts[name]
        if not isinstance(identity, dict) or set(identity) != {"sha256", "size"}:
            fail("manifest artifact identity is malformed: {}".format(name))
        path = os.path.join(staging, name)
        actual_sha = sha256_file(path)
        if identity.get("sha256") != actual_sha or identity.get("size") != os.path.getsize(path):
            fail("manifest artifact identity differs: {}".format(name))

    checksums = parse_checksums(os.path.join(staging, "SHA256SUMS"))
    for name in sorted(CHECKSUM_NAMES):
        if sha256_file(os.path.join(staging, name)) != checksums[name]:
            fail("SHA256SUMS verification failed: {}".format(name))
    verify_admin_and_extract(os.path.join(staging, "joysong-admin.tar.gz"), staging)
    return migration_sha


def main(argv):
    if len(argv) == 5 and argv[1] == "freeze":
        freeze_bundle(argv[2], argv[3], argv[4])
        return
    if len(argv) == 3 and argv[1] == "migration-sha":
        if not os.path.isabs(argv[2]):
            fail("server JAR path must be absolute")
        print(validate_jar_and_migrations(argv[2]))
        return
    if len(argv) != 7:
        fail("usage: verify-uat-release.py BUNDLE STAGING TAG COMMIT RUN_ID BUNDLE_SHA256")
    bundle, staging, tag, commit, run_id, expected_sha = argv[1:]
    if not os.path.isabs(bundle) or not os.path.isabs(staging):
        fail("bundle and staging paths must be absolute")
    if not TAG_RE.fullmatch(tag):
        fail("invalid UAT tag")
    if not COMMIT_RE.fullmatch(commit):
        fail("invalid commit")
    if not RUN_ID_RE.fullmatch(run_id) or int(run_id) > RUN_ID_MAX:
        fail("invalid run ID")
    if not LOWER_SHA_RE.fullmatch(expected_sha):
        fail("invalid bundle SHA-256")
    actual_sha = sha256_file(bundle)
    if actual_sha != expected_sha:
        fail("deployment bundle SHA-256 mismatch")
    verify_outer_and_extract(bundle, staging)
    migration_sha = validate_identity(staging, tag, commit, run_id)
    print("BUNDLE_SHA256={}".format(actual_sha))
    print("DATABASE_MIGRATIONS_SHA256={}".format(migration_sha))


if __name__ == "__main__":
    try:
        main(sys.argv)
    except ContractError as error:
        print("UAT release verification failed: {}".format(error), file=sys.stderr)
        sys.exit(2)
