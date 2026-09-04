#!/usr/bin/env python3
import base64
import hashlib
import json
import os
import stat
import sys


CHUNK_SIZE = 1024 * 1024
STABLE_FIELDS = (
    "st_dev",
    "st_ino",
    "st_mode",
    "st_nlink",
    "st_uid",
    "st_gid",
    "st_size",
    "st_mtime_ns",
    "st_ctime_ns",
)


def fail(message):
    raise SystemExit(message)


def stable_stat_matches(first, second):
    return all(getattr(first, field) == getattr(second, field) for field in STABLE_FIELDS)


def validated_absolute_path(path_value, label):
    encoded = os.fsencode(path_value)
    if (not os.path.isabs(encoded) or os.path.normpath(encoded) != encoded or
            b"\0" in encoded or b"\n" in encoded or b"\r" in encoded):
        fail("{} must be a normalized absolute path".format(label))
    return encoded


def validate_entry(relative_path, entry_stat, root_device):
    if entry_stat.st_dev != root_device:
        fail("baseline data crosses a filesystem boundary")
    if stat.S_ISLNK(entry_stat.st_mode):
        fail("baseline data contains a symbolic link")
    if not (stat.S_ISDIR(entry_stat.st_mode) or stat.S_ISREG(entry_stat.st_mode)):
        fail("baseline data contains a special file")
    if stat.S_ISREG(entry_stat.st_mode) and entry_stat.st_nlink != 1:
        fail("baseline data contains a hard-linked regular file")
    if relative_path.startswith(b"/") or b"\0" in relative_path:
        fail("baseline data contains an unsafe relative path")


def record(entry_stat, relative_path, entry_type, digest=None):
    return {
        "gid": entry_stat.st_gid,
        "mode": stat.S_IMODE(entry_stat.st_mode),
        "pathBase64": base64.b64encode(relative_path).decode("ascii"),
        "pathEncoding": "base64",
        "schemaVersion": 1,
        "sha256": digest,
        "size": entry_stat.st_size if entry_type == "file" else None,
        "type": entry_type,
        "uid": entry_stat.st_uid,
    }


def open_directory(path_value, expected_stat, root_device):
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path_value, flags)
    try:
        opened_stat = os.fstat(descriptor)
        validate_entry(b"", opened_stat, root_device)
        if not stat.S_ISDIR(opened_stat.st_mode) or not stable_stat_matches(expected_stat, opened_stat):
            fail("baseline data directory changed before scanning")
    except BaseException:
        os.close(descriptor)
        raise
    return descriptor, opened_stat


def file_record(parent_descriptor, name, relative_path, expected_stat, root_device):
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(name, flags, dir_fd=parent_descriptor)
    try:
        opened_stat = os.fstat(descriptor)
        validate_entry(relative_path, opened_stat, root_device)
        if not stat.S_ISREG(opened_stat.st_mode) or not stable_stat_matches(expected_stat, opened_stat):
            fail("baseline data file changed before hashing")
        value = hashlib.sha256()
        while True:
            chunk = os.read(descriptor, CHUNK_SIZE)
            if not chunk:
                break
            value.update(chunk)
        final_stat = os.fstat(descriptor)
        if not stable_stat_matches(opened_stat, final_stat):
            fail("baseline data file changed while hashing")
    finally:
        os.close(descriptor)
    current_stat = os.stat(name, dir_fd=parent_descriptor, follow_symlinks=False)
    if not stable_stat_matches(final_stat, current_stat):
        fail("baseline data file changed after hashing")
    return record(final_stat, relative_path, "file", value.hexdigest())


def scan_directory(directory_descriptor, relative_directory, directory_stat, root_device, records):
    try:
        names = os.listdir(directory_descriptor)
    except OSError as error:
        fail("baseline data directory cannot be listed: {}".format(error))
    encoded_names = []
    for name in names:
        encoded_name = name if isinstance(name, bytes) else os.fsencode(name)
        if encoded_name in (b"", b".", b"..") or b"/" in encoded_name or b"\0" in encoded_name:
            fail("baseline data contains an unsafe entry name")
        encoded_names.append(encoded_name)
    if len(encoded_names) != len(set(encoded_names)):
        fail("baseline data contains duplicate raw entry names")

    for name in sorted(encoded_names):
        relative_path = name if relative_directory == b"" else relative_directory + b"/" + name
        entry_stat = os.stat(name, dir_fd=directory_descriptor, follow_symlinks=False)
        validate_entry(relative_path, entry_stat, root_device)
        if stat.S_ISDIR(entry_stat.st_mode):
            child_descriptor, opened_stat = open_directory_at(
                directory_descriptor,
                name,
                relative_path,
                entry_stat,
                root_device,
            )
            records.append((relative_path, record(opened_stat, relative_path, "directory")))
            try:
                scan_directory(child_descriptor, relative_path, opened_stat, root_device, records)
                final_stat = os.fstat(child_descriptor)
                if not stable_stat_matches(opened_stat, final_stat):
                    fail("baseline data directory changed while scanning")
            finally:
                os.close(child_descriptor)
            current_stat = os.stat(name, dir_fd=directory_descriptor, follow_symlinks=False)
            if not stable_stat_matches(final_stat, current_stat):
                fail("baseline data directory changed after scanning")
        else:
            records.append((
                relative_path,
                file_record(directory_descriptor, name, relative_path, entry_stat, root_device),
            ))

    final_directory_stat = os.fstat(directory_descriptor)
    if not stable_stat_matches(directory_stat, final_directory_stat):
        fail("baseline data directory changed while scanning")


def open_directory_at(parent_descriptor, name, relative_path, expected_stat, root_device):
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(name, flags, dir_fd=parent_descriptor)
    try:
        opened_stat = os.fstat(descriptor)
        validate_entry(relative_path, opened_stat, root_device)
        if not stat.S_ISDIR(opened_stat.st_mode) or not stable_stat_matches(expected_stat, opened_stat):
            fail("baseline data directory changed before scanning")
    except BaseException:
        os.close(descriptor)
        raise
    return descriptor, opened_stat


def manifest_bytes(data_root):
    root_lstat = os.lstat(data_root)
    if not stat.S_ISDIR(root_lstat.st_mode) or stat.S_ISLNK(root_lstat.st_mode):
        fail("baseline data root is not a real directory")
    root_device = root_lstat.st_dev
    root_descriptor, root_stat = open_directory(data_root, root_lstat, root_device)
    records = [(b"", record(root_stat, b"", "directory"))]
    try:
        scan_directory(root_descriptor, b"", root_stat, root_device, records)
        final_root_stat = os.fstat(root_descriptor)
        if not stable_stat_matches(root_stat, final_root_stat):
            fail("baseline data root changed while scanning")
    finally:
        os.close(root_descriptor)
    final_root_lstat = os.lstat(data_root)
    if not stable_stat_matches(final_root_stat, final_root_lstat):
        fail("baseline data root changed after scanning")

    records.sort(key=lambda item: item[0])
    lines = []
    directories = 0
    files = 0
    for _, record_value in records:
        if record_value["type"] == "directory":
            directories += 1
        else:
            files += 1
        lines.append(json.dumps(
            record_value,
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("ascii") + b"\n")
    return b"".join(lines), directories, files


def stable_manifest(data_root):
    first_bytes, first_directories, first_files = manifest_bytes(data_root)
    second_bytes, second_directories, second_files = manifest_bytes(data_root)
    if ((first_directories, first_files) != (second_directories, second_files) or
            first_bytes != second_bytes):
        fail("baseline data changed between manifest scans")
    return first_bytes, first_directories, first_files


def require_manifest_parent(manifest_path):
    parent = os.path.dirname(manifest_path)
    if not parent or os.path.realpath(parent) != parent:
        fail("manifest parent must be a canonical real directory")
    parent_stat = os.lstat(parent)
    if not stat.S_ISDIR(parent_stat.st_mode) or stat.S_ISLNK(parent_stat.st_mode):
        fail("manifest parent must be a real directory")
    return parent


def require_root_only_manifest(manifest_path):
    manifest_stat = os.lstat(manifest_path)
    if (not stat.S_ISREG(manifest_stat.st_mode) or stat.S_ISLNK(manifest_stat.st_mode) or
            manifest_stat.st_nlink != 1 or manifest_stat.st_uid != 0 or manifest_stat.st_gid != 0 or
            stat.S_IMODE(manifest_stat.st_mode) != 0o600):
        fail("baseline data manifest must be a root:root mode 0600 regular non-link file")
    descriptor = os.open(manifest_path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        opened_stat = os.fstat(descriptor)
        if not stable_stat_matches(manifest_stat, opened_stat):
            fail("baseline data manifest changed before reading")
        chunks = []
        while True:
            chunk = os.read(descriptor, CHUNK_SIZE)
            if not chunk:
                break
            chunks.append(chunk)
        final_stat = os.fstat(descriptor)
        if not stable_stat_matches(opened_stat, final_stat):
            fail("baseline data manifest changed while reading")
    finally:
        os.close(descriptor)
    return b"".join(chunks)


def create_manifest(manifest_path, content):
    if os.path.lexists(manifest_path):
        fail("baseline data manifest target must not exist")
    parent = require_manifest_parent(manifest_path)
    stage = os.path.join(
        parent,
        b"." + os.path.basename(manifest_path) + b".tmp." +
        str(os.getpid()).encode("ascii") + b"." + os.urandom(8).hex().encode("ascii"),
    )
    descriptor = os.open(stage, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    completed = False
    try:
        os.fchown(descriptor, 0, 0)
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            descriptor = -1
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        if os.path.lexists(manifest_path):
            fail("baseline data manifest target appeared during creation")
        os.link(stage, manifest_path, follow_symlinks=False)
        os.unlink(stage)
        directory_descriptor = os.open(
            parent,
            os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0),
        )
        try:
            os.fsync(directory_descriptor)
        finally:
            os.close(directory_descriptor)
        completed = True
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        if os.path.lexists(stage):
            os.unlink(stage)
    if not completed:
        fail("baseline data manifest was not created")
    stored = require_root_only_manifest(manifest_path)
    if stored != content:
        fail("stored baseline data manifest differs from generated bytes")


def print_result(content, directories, files, manifest_path=None, data_root=None):
    print("DATA_MANIFEST_DIRECTORY_COUNT={}".format(directories))
    print("DATA_MANIFEST_FILE_COUNT={}".format(files))
    print("DATA_MANIFEST_ENTRY_COUNT={}".format(directories + files))
    print("DATA_MANIFEST_SHA256={}".format(hashlib.sha256(content).hexdigest()))
    if manifest_path is not None:
        print("DATA_MANIFEST_PATH={}".format(os.fsdecode(manifest_path)))
    elif data_root is not None:
        print("DATA_ROOT={}".format(os.fsdecode(data_root)))


def main():
    if getattr(os, "geteuid", lambda: 1)() != 0:
        fail("baseline data manifest operations must run as root")
    if not hasattr(os, "O_NOFOLLOW") or not hasattr(os, "O_DIRECTORY"):
        fail("baseline data manifest operations require Linux no-follow directory support")
    if len(sys.argv) not in (3, 4) or sys.argv[1] not in ("check", "create", "verify"):
        fail("usage: baseline-data-manifest.py <check|create|verify> <absolute-data-root> [absolute-manifest]")
    operation = sys.argv[1]
    if operation == "check" and len(sys.argv) != 3:
        fail("check requires exactly one absolute data root")
    if operation != "check" and len(sys.argv) != 4:
        fail("create and verify require an absolute data root and absolute manifest")

    data_root = validated_absolute_path(sys.argv[2], "data root")
    if os.path.realpath(data_root) != data_root:
        fail("data root must be a canonical path without symbolic-link traversal")
    manifest_path = None
    stored_manifest = None
    if operation != "check":
        manifest_path = validated_absolute_path(sys.argv[3], "manifest")
        require_manifest_parent(manifest_path)
        if os.path.realpath(manifest_path) != manifest_path:
            fail("manifest must be a canonical path without symbolic-link traversal")
        if os.path.commonpath((data_root, manifest_path)) == data_root:
            fail("manifest must not be stored inside the data root")
        if operation == "create" and os.path.lexists(manifest_path):
            fail("baseline data manifest target must not exist")
        if operation == "verify":
            stored_manifest = require_root_only_manifest(manifest_path)

    content, directories, files = stable_manifest(data_root)
    if operation == "create":
        create_manifest(manifest_path, content)
    elif operation == "verify":
        if stored_manifest != content:
            fail("baseline data manifest does not match both rebuilt scans")
    print_result(content, directories, files, manifest_path, data_root)


if __name__ == "__main__":
    main()
