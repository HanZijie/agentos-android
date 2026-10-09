#!/usr/bin/env python3
"""Assemble the GitHub Release folder: everything a user downloads, checked, with one SHA256SUMS.

    python3 tools/package-release.py [--out DIR] [--allow-unsigned] [--skip-gradle] [--skip-pi] [--samples LIST]
    python3 tools/package-release.py --check DIR

Steps:
  1. tools/package-module.py   -> agentos-<ver>.zip (the Magisk / KernelSU module with the AgentOS App inside)
  2. tools/package-samples.py  -> AgentOS-Sample-<name>-<ver>.apk (the sample apps), RELEASE-NOTES.md, build-info.json of the samples
  3. copy tools/install.sh and tools/check-device.sh, docs/install.md (as INSTALL.md) and the bilingual release notes
  4. one SHA256SUMS over every file (sha256sum -c format) and one build-info.json
  5. `--check`: the same checks again on the finished folder (also what the release workflow / the maintainer runs before uploading)

The folder is what `tools/install.sh` expects: all files side by side. Nothing is uploaded here; see docs/runbooks/release.md for the upload.

Signing: with AGENTOS_SIGNING_* in the environment (README, "项目发布证书") the APKs are signed and the folder is a release. Without them the
build is refused unless --allow-unsigned, which gives an `-unsigned` dry run that tools/install.sh refuses to install. There is no debug
variant: the checks of tools/package-samples.py (no debug receivers, permission allowlist, one signing certificate) are written for release
builds and are not loosened for test builds.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HERE = Path(__file__).resolve().parent
SAMPLES = ("alarm", "calendar", "notes", "todo", "sms")


class ReleaseError(Exception):
    pass


def log(msg: str) -> None:
    print(f"[package-release] {msg}", flush=True)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def run(cmd: list[str]) -> None:
    log("$ " + " ".join(cmd))
    subprocess.run(cmd, cwd=ROOT, check=True)


def gradle_version() -> tuple[str, str]:
    props = {}
    for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return props["agentos.version"], props["agentos.versionCode"]


# ------------------------------------------------------------------------------------------ pure helpers (unit tested)

def sums_text(entries: list[tuple[str, str]]) -> str:
    """`sha256sum -c` format, sorted by file name."""
    return "".join(f"{digest}  {name}\n" for name, digest in sorted(entries))


def parse_sums(text: str) -> dict[str, str]:
    out = {}
    for line in text.splitlines():
        m = re.fullmatch(r"([0-9a-f]{64}) [ *](.+)", line)
        if m:
            out[m.group(2)] = m.group(1)
    return out


def expected_names(version: str, samples: tuple[str, ...], unsigned: bool) -> set[str]:
    suffix = "-unsigned" if unsigned else ""
    names = {f"agentos-{version}{suffix}.zip", "install.sh", "check-device.sh", "INSTALL.md", "RELEASE-NOTES.md", "build-info.json"}
    names |= {f"AgentOS-Sample-{s}-{version}{suffix}.apk" for s in samples}
    return names


def check_folder(folder: Path) -> list[str]:
    """Problems of a finished release folder (an empty list = fine)."""
    problems: list[str] = []
    info_path = folder / "build-info.json"
    sums_path = folder / "SHA256SUMS"
    if not info_path.is_file():
        return [f"{folder}: no build-info.json"]
    if not sums_path.is_file():
        return [f"{folder}: no SHA256SUMS"]
    info = json.loads(info_path.read_text(encoding="utf-8"))
    version = info.get("version", "")
    samples = tuple(info.get("samples", []))
    unsigned = bool(info.get("dryRun"))
    want = expected_names(version, samples, unsigned)
    present = {p.name for p in folder.iterdir() if p.is_file()} - {"SHA256SUMS"}
    for n in sorted(want - present):
        problems.append(f"missing file: {n}")
    for n in sorted(present - want):
        problems.append(f"unexpected file: {n}")
    sums = parse_sums(sums_path.read_text(encoding="utf-8"))
    # build-info.json is not listed in SHA256SUMS: it contains the digests of the others and install.sh reads it only for the variant
    for n in sorted(present - {"build-info.json"}):
        if n not in sums:
            problems.append(f"{n} is not listed in SHA256SUMS")
        elif sums[n] != sha256_file(folder / n):
            problems.append(f"{n}: SHA-256 differs from SHA256SUMS")
    for n in sorted(set(sums) - present):
        problems.append(f"SHA256SUMS lists {n} which is not in the folder")
    # the zip has to be one tools/install.sh can flash
    zname = f"agentos-{version}{'-unsigned' if unsigned else ''}.zip"
    zpath = folder / zname
    if zpath.is_file():
        with zipfile.ZipFile(zpath) as z:
            names = set(z.namelist())
            for need in ("module.prop", "common.sh", "support-matrix.yaml", "app/apks.list", "customize.sh", "service.sh"):
                if need not in names:
                    problems.append(f"{zname}: {need} is missing")
            if "module.prop" in names:
                m = re.search(r"^version=(.*)$", z.read("module.prop").decode("utf-8"), re.M)
                if not m or m.group(1).strip() != version:
                    problems.append(f"{zname}: module.prop version {m.group(1).strip() if m else None} != {version}")
    # a release must carry the signing certificate of every APK and it must be the same one
    if not unsigned:
        certs = {a.get("signingCertSha256") for a in info.get("sampleApks", [])} | {info.get("moduleAppCertSha256")}
        if None in certs or len(certs) != 1:
            problems.append(f"signing certificates differ or are missing: {sorted(c or 'none' for c in certs)}")
    notes = folder / "RELEASE-NOTES.md"
    if notes.is_file():
        t = notes.read_text(encoding="utf-8")
        if "已知限制" not in t or not re.search(r"known limitations", t, re.I):
            problems.append("RELEASE-NOTES.md must have 已知限制 and Known limitations")
    if "\ufffd" in "".join((folder / n).read_text(encoding="utf-8", errors="replace") for n in ("INSTALL.md", "RELEASE-NOTES.md") if (folder / n).is_file()):
        problems.append("INSTALL.md or RELEASE-NOTES.md contains a U+FFFD replacement character")
    return problems


# ------------------------------------------------------------------------------------------ assemble

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--out", type=Path, default=ROOT / "dist-release")
    ap.add_argument("--allow-unsigned", action="store_true", help="offline dry run: unsigned APKs, files get an -unsigned suffix, install.sh refuses them")
    ap.add_argument("--skip-gradle", action="store_true", help="use the APKs that are already built")
    ap.add_argument("--skip-pi", action="store_true", help="do not run core/pi-runtime/build.mjs (the bundle is already in app/src/main/assets)")
    ap.add_argument("--samples", default=",".join(SAMPLES), help="comma list (default: all five)")
    ap.add_argument("--check", type=Path, help="only check an existing release folder")
    args = ap.parse_args()

    try:
        if args.check:
            problems = check_folder(args.check)
            for p in problems:
                print(f"ERROR {p}")
            print(f"{args.check}: {'OK' if not problems else f'{len(problems)} problem(s)'}")
            return 1 if problems else 0

        version, version_code = gradle_version()
        samples = tuple(s for s in args.samples.split(",") if s)
        for s in samples:
            if s not in SAMPLES:
                raise ReleaseError(f"unknown sample app: {s}")
        out = args.out
        if out.exists() and any(out.iterdir()):
            raise ReleaseError(f"{out} is not empty; pass an empty --out (nothing is deleted for you)")
        # A fresh working folder for every run (under build/, which is git-ignored): nothing from an earlier run can leak into this one and
        # nothing has to be cleaned up by hand. It stays after the run (the raw output of the two packaging scripts, useful when something
        # looks wrong); the finished release folder is only ever --out.
        (ROOT / "build").mkdir(exist_ok=True)
        work = Path(tempfile.mkdtemp(prefix="release-work-", dir=ROOT / "build"))
        mod_out, smp_out = work / "module", work / "samples"
        mod_out.mkdir()
        smp_out.mkdir()
        log(f"working folder {work}")

        module_cmd = [sys.executable, str(HERE / "package-module.py"), "--variant", "release", "--out", str(mod_out)]
        if args.allow_unsigned:
            module_cmd.append("--allow-unsigned")
        if args.skip_gradle:
            module_cmd.append("--skip-gradle")
        if args.skip_pi:
            module_cmd.append("--skip-pi")
        run(module_cmd)

        samples_cmd = [sys.executable, str(HERE / "package-samples.py"), "--out", str(smp_out), "--only", ",".join(samples),
                       "--notes", str(ROOT / "docs" / "release-notes" / f"{version}.md"), "--skip-conformance"]
        if args.allow_unsigned:
            samples_cmd.append("--allow-unsigned")
        else:
            app_apk = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
            if app_apk.is_file():
                samples_cmd += ["--app-apk", str(app_apk)]
        if args.skip_gradle:
            samples_cmd.append("--skip-gradle")
        run(samples_cmd)

        out.mkdir(parents=True, exist_ok=True)
        for f in sorted(mod_out.glob("agentos-*.zip")):
            shutil.copy2(f, out / f.name)
        for f in sorted(smp_out.glob("AgentOS-Sample-*.apk")):
            shutil.copy2(f, out / f.name)
        shutil.copy2(smp_out / "RELEASE-NOTES.md", out / "RELEASE-NOTES.md")
        shutil.copy2(HERE / "install.sh", out / "install.sh")
        (out / "install.sh").chmod(0o755)
        shutil.copy2(HERE / "check-device.sh", out / "check-device.sh")
        install_md = ROOT / "docs" / "install.md"
        if not install_md.is_file():
            raise ReleaseError("docs/install.md does not exist")
        shutil.copy2(install_md, out / "INSTALL.md")

        module_info = json.loads((mod_out / "build-info.json").read_text(encoding="utf-8"))
        samples_info = json.loads((smp_out / "build-info.json").read_text(encoding="utf-8"))
        rev = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
        dirty = bool(subprocess.run(["git", "status", "--porcelain", "--untracked-files=no"], cwd=ROOT, capture_output=True, text=True).stdout.strip())
        unsigned = bool(samples_info.get("dryRun"))
        app_entry = next((a for a in module_info.get("apks", []) if a.get("package") == "org.agentos.app"), {})
        info = {
            "version": version, "versionCode": int(version_code),
            "variant": "release", "dryRun": unsigned,
            "gitRevision": rev, "gitDirty": dirty, "samples": list(samples),
            "module": {"file": module_info["zip"], "sha256": module_info["sha256"]},
            "moduleAppCertSha256": app_entry.get("certSha256"),
            "sampleApks": [{"file": a["file"], "sample": a["sample"], "package": a["package"], "sha256": a["sha256"],
                            "signingCertSha256": a["signingCertSha256"], "versionCode": a["versionCode"], "permissions": a["permissions"]}
                           for a in samples_info["apks"]],
        }
        (out / "build-info.json").write_text(json.dumps(info, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        entries = [(p.name, sha256_file(p)) for p in out.iterdir() if p.is_file() and p.name not in ("SHA256SUMS", "build-info.json")]
        (out / "SHA256SUMS").write_text(sums_text(entries), encoding="utf-8")

        problems = check_folder(out)
        if problems:
            for p in problems:
                print(f"ERROR {p}")
            raise ReleaseError(f"{out} failed its own checks")
        log(f"OK {out}: {len(entries)} files + SHA256SUMS + build-info.json" + (" (UNSIGNED DRY RUN: not a release)" if unsigned else ""))
        if dirty:
            log("WARNING: the working tree has uncommitted changes; gitRevision does not describe these files")
        return 0
    except (ReleaseError, subprocess.CalledProcessError) as e:
        print(f"[package-release] ERROR: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
