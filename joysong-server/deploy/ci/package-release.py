#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import tarfile
import tempfile
import zipfile
from pathlib import Path
from urllib.parse import urlsplit


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_regular_file(path: Path, label: str) -> None:
    if path.is_symlink() or not path.is_file() or path.stat().st_size == 0:
        raise ValueError(f"{label} must be a non-empty regular file")


def database_migrations_sha256(jar: Path) -> str:
    digest = hashlib.sha256()
    with zipfile.ZipFile(jar) as archive:
        names = sorted(
            info.filename
            for info in archive.infolist()
            if not info.is_dir() and "/db/migration/" in f"/{info.filename}"
        )
        if len(names) != len(set(names)):
            raise ValueError("server JAR contains duplicate database migration entries")
        for name in names:
            digest.update(name.encode("utf-8"))
            digest.update(b"\0")
            digest.update(hashlib.sha256(archive.read(name)).digest())
    return digest.hexdigest()


def validate_origins(environment: str, api_url: str, admin_url: str) -> None:
    values = []
    for label, value in (("API", api_url), ("Admin", admin_url)):
        parsed = urlsplit(value)
        if (
            parsed.scheme != "https"
            or not parsed.hostname
            or parsed.port not in (None, 443)
            or parsed.path not in ("", "/")
            or parsed.username
            or parsed.password
            or parsed.query
            or parsed.fragment
        ):
            raise ValueError(f"{label} base URL must be an exact HTTPS origin")
        values.append(parsed.hostname)
    allowed = (
        {
            ("api.joyingsong.net", "joyingsong.net"),
            ("api-uat.joyingsong.net", "uat.joyingsong.net"),
        }
        if environment == "uat"
        else {("api.joyingsong.net", "joyingsong.net")}
    )
    if tuple(values) not in allowed:
        raise ValueError("API/Admin base URL pair is not approved for the target environment")


def validate_regular_tree(root: Path, label: str) -> None:
    if root.is_symlink() or not root.is_dir():
        raise ValueError(f"{label} must be a real directory")
    for candidate in root.rglob("*"):
        relative = candidate.relative_to(root).as_posix()
        if candidate.is_symlink() or not (candidate.is_dir() or candidate.is_file()):
            raise ValueError(f"{label} contains a link or special file: {relative}")


def safe_extract(archive: Path, destination: Path) -> None:
    require_regular_file(archive, "admin archive")
    with tarfile.open(archive, "r:gz") as bundle:
        destination_root = destination.resolve()
        for member in bundle.getmembers():
            resolved = (destination / member.name).resolve()
            if destination_root not in (resolved, *resolved.parents):
                raise ValueError(f"unsafe archive member: {member.name}")
            if not (member.isfile() or member.isdir()):
                raise ValueError(f"links and special files are not permitted in admin archive: {member.name}")
        bundle.extractall(destination, filter="data")


def add_tree(archive: tarfile.TarFile, source: Path, arcname: str) -> None:
    def normalize(info: tarfile.TarInfo) -> tarfile.TarInfo:
        info.uid = info.gid = 0
        info.uname = info.gname = "root"
        info.mtime = 0
        return info

    archive.add(source, arcname=arcname, recursive=True, filter=normalize)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--environment", choices=("uat", "prod"), required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--build-number", type=int, required=True)
    parser.add_argument("--api-base-url", required=True)
    parser.add_argument("--admin-base-url", required=True)
    parser.add_argument("--jar", type=Path, required=True)
    admin = parser.add_mutually_exclusive_group(required=True)
    admin.add_argument("--admin-dir", type=Path)
    admin.add_argument("--admin-archive", type=Path)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--sbom", type=Path, required=True)
    parser.add_argument("--scan-report", type=Path, required=True)
    args = parser.parse_args()

    if len(args.commit) != 40 or any(c not in "0123456789abcdef" for c in args.commit):
        raise ValueError("commit must be a full lowercase Git SHA")
    tag_pattern = (
        r"v[0-9]+\.[0-9]+\.[0-9]+-uat\.[0-9]+"
        if args.environment == "uat"
        else r"v[0-9]+\.[0-9]+\.[0-9]+"
    )
    if not re.fullmatch(tag_pattern, args.tag):
        raise ValueError("tag does not match the target environment")
    if args.build_number < 1:
        raise ValueError("build number must be a positive integer")
    validate_origins(args.environment, args.api_base_url, args.admin_base_url)
    require_regular_file(args.jar, "server JAR")
    require_regular_file(args.sbom, "SBOM")
    require_regular_file(args.scan_report, "security scan report")
    if args.apk:
        require_regular_file(args.apk, "Android APK")
    args.output.mkdir(parents=True, exist_ok=True)
    if any(args.output.iterdir()):
        raise ValueError("output directory must be empty")

    jar_out = args.output / "server.jar"
    admin_out = args.output / "admin.tar.gz"
    sbom_out = args.output / "sbom.cdx.json"
    scan_report_out = args.output / "trivy-results.json"
    shutil.copy2(args.jar, jar_out)
    shutil.copy2(args.sbom, sbom_out)
    shutil.copy2(args.scan_report, scan_report_out)

    with tempfile.TemporaryDirectory() as temporary:
        temporary_root = Path(temporary)
        admin_root = temporary_root / "admin"
        if args.admin_dir:
            validate_regular_tree(args.admin_dir, "admin build")
            if not (args.admin_dir / "index.html").is_file():
                raise ValueError("admin build is missing index.html")
            shutil.copytree(args.admin_dir, admin_root)
            with tarfile.open(admin_out, "w:gz") as archive:
                add_tree(archive, admin_root, "admin")
        else:
            safe_extract(args.admin_archive, temporary_root)
            if not (admin_root / "index.html").is_file():
                raise ValueError("admin archive is missing admin/index.html")
            shutil.copy2(args.admin_archive, admin_out)
        validate_regular_tree(admin_root, "admin build")

        release_identity = {
            "schemaVersion": 1,
            "environment": args.environment,
            "tag": args.tag,
            "commit": args.commit,
            "buildNumber": args.build_number,
            "apiBaseUrl": args.api_base_url,
            "adminBaseUrl": args.admin_base_url,
            "databaseMigrationsSha256": database_migrations_sha256(jar_out),
        }
        release_identity_out = args.output / "release.json"
        release_identity_out.write_text(
            json.dumps(release_identity, ensure_ascii=False, sort_keys=True) + "\n",
            encoding="utf-8",
        )

        artifacts = [
            {"name": "server.jar", "sha256": sha256(jar_out)},
            {"name": "admin.tar.gz", "sha256": sha256(admin_out)},
            {"name": "sbom.cdx.json", "sha256": sha256(sbom_out)},
            {"name": "trivy-results.json", "sha256": sha256(scan_report_out)},
            {"name": "release.json", "sha256": sha256(release_identity_out)},
        ]
        if args.apk:
            apk_out = args.output / "android.apk"
            shutil.copy2(args.apk, apk_out)
            artifacts.append({"name": apk_out.name, "sha256": sha256(apk_out)})

        manifest = {
            "schemaVersion": 1,
            "environment": args.environment,
            "tag": args.tag,
            "commit": args.commit,
            "apiBaseUrl": release_identity["apiBaseUrl"],
            "adminBaseUrl": release_identity["adminBaseUrl"],
            "databaseMigrationsSha256": release_identity["databaseMigrationsSha256"],
            "artifacts": artifacts,
            "buildNumber": args.build_number,
        }
        manifest_out = args.output / "manifest.json"
        manifest_out.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )

        server_root = temporary_root / "server-bundle"
        server_root.mkdir()
        shutil.copy2(jar_out, server_root / jar_out.name)
        shutil.copy2(admin_out, server_root / admin_out.name)
        shutil.copytree(admin_root, server_root / "admin")
        shutil.copy2(manifest_out, server_root / manifest_out.name)
        shutil.copy2(release_identity_out, server_root / release_identity_out.name)
        bundle_out = args.output / f"joysong-ecs-{args.tag}.tar.gz"
        with tarfile.open(bundle_out, "w:gz") as archive:
            for child in sorted(server_root.iterdir(), key=lambda item: item.name):
                add_tree(archive, child, child.name)

    checksum_paths = sorted(
        path for path in args.output.iterdir() if path.is_file() and path.name != "SHA256SUMS"
    )
    (args.output / "SHA256SUMS").write_text(
        "".join(f"{sha256(path)}  {path.name}\n" for path in checksum_paths),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
