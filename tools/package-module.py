#!/usr/bin/env python3
"""Build agentos-<version>.zip: scripts, the AgentOS App APK (the Runner from W21) and the support matrix.

    python3 tools/package-module.py [--variant release|debug] [--with-runner] [--skip-pi] [--skip-gradle]
                                    [--app-apk PATH] [--runner-apk PATH] [--out DIR] [--allow-unsigned]
                                    [--gradle-arg ARG ...]
    python3 tools/package-module.py --check path/to/agentos-<version>.zip

Steps (docs/implementation-plan.md W7, section 2):
  1. core/pi-runtime: `npm ci` when node_modules is missing, then `node build.mjs` (pi-agent.js and
     model-catalog.json into app/src/main/assets/). Skipped with a warning while build.mjs does not exist.
  2. Gradle: :app:assemble<Variant> (and :runner with --with-runner), with -Pagentos.skipPiBundle=true
     because step 1 already produced the bundle.
  3. Stage the module from an allowlist, render module.prop, write app/apks.list
     (<package> <versionCode> <path> <APK SHA-256> <signing cert SHA-256>).
  4. Offline checks (also `--check ZIP`): file allowlist (no system/, post-fs-data.sh, sepolicy.rule,
     native binaries ...), `sh -n` on every script, module.prop, support-matrix.yaml, apks.list hashes.
  5. Deterministic zip (sorted entries, fixed timestamps from SOURCE_DATE_EPOCH or the HEAD commit),
     plus <zip>.sha256 and build-info.json.

Signing: release APKs are signed by Gradle from AGENTOS_SIGNING_* environment variables (docs/development.md
"项目发布证书"). An unsigned APK cannot be installed by `pm`, so it is refused unless --allow-unsigned (CI offline
checks only; the zip is then named *-unsigned.zip). Secrets are never read or printed here.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODULE_SRC = ROOT / "module"
PI_DIR = ROOT / "core" / "pi-runtime"
ASSETS = ROOT / "app" / "src" / "main" / "assets"

MODULE_ID = "agentos"
APP_PACKAGE = "org.agentos.app"
RUNNER_PACKAGE = "org.agentos.runner"

# Files copied from module/ into the zip. Everything else in module/ (test/, templates) stays out.
MODULE_FILES = [
    "META-INF/com/google/android/update-binary",
    "META-INF/com/google/android/updater-script",
    "customize.sh",
    "service.sh",
    "uninstall.sh",
    "common.sh",
    "apks.sh",
    "support-matrix.yaml",
]
OPTIONAL_MODULE_FILES = ["action.sh"]  # W11

# What a zip may contain (architecture section 4: scripts, two APKs, the support matrix).
ALLOWED = [
    re.compile(r"^META-INF/com/google/android/(update-binary|updater-script)$"),
    re.compile(r"^(customize|service|uninstall|action|common|apks)\.sh$"),
    re.compile(r"^module\.prop$"),
    re.compile(r"^support-matrix\.yaml$"),
    re.compile(r"^app/apks\.list$"),
    re.compile(r"^app/AgentOS(-Runner)?-[A-Za-z0-9._-]+\.apk$"),
]
# Named explicitly so the error says why (principle 7: no mounts, no sepolicy, nothing at post-fs-data).
FORBIDDEN = [
    (re.compile(r"^system/"), "files under system/ would be mounted into /system"),
    (re.compile(r"^(vendor|product|system_ext)/"), "partition overlays are not allowed"),
    (re.compile(r"^post-fs-data\.sh$"), "nothing may run at post-fs-data"),
    (re.compile(r"^boot-completed\.sh$"), "only service.sh runs at boot"),
    (re.compile(r"^sepolicy\.rule$"), "no SELinux rules"),
    (re.compile(r"^system\.prop$"), "no system properties"),
    (re.compile(r"^zygisk/"), "no Zygisk code"),
    (re.compile(r"^webroot/"), "no WebUI"),
    (re.compile(r"^skip_mount$|^disable$|^remove$|^update$"), "module state flags must not ship in the zip"),
]
EXECUTABLE = re.compile(r"(^META-INF/com/google/android/update-binary$|\.sh$)")


class PackagingError(Exception):
    pass


def log(msg: str) -> None:
    print(f"[package-module] {msg}", flush=True)


def run(cmd: list[str], cwd: Path | None = None) -> None:
    log("$ " + " ".join(cmd))
    subprocess.run(cmd, cwd=cwd, check=True)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def gradle_properties() -> dict[str, str]:
    props: dict[str, str] = {}
    for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return props


# ---------------------------------------------------------------------------------------- SDK tools

def android_home() -> Path:
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(key):
            return Path(os.environ[key])
    local = ROOT / "local.properties"
    if local.is_file():
        for line in local.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].strip())
    default = Path.home() / "Library" / "Android" / "sdk"
    if default.is_dir():
        return default
    raise PackagingError("Android SDK not found: set ANDROID_HOME")


def build_tool(name: str) -> Path:
    bt = android_home() / "build-tools"
    versions = sorted((p for p in bt.iterdir() if (p / name).exists()), key=lambda p: [
        int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.name)])
    if not versions:
        raise PackagingError(f"{name} not found under {bt}")
    return versions[-1] / name


def apk_badging(apk: Path) -> dict[str, str]:
    out = subprocess.run([str(build_tool("aapt2")), "dump", "badging", str(apk)],
                         check=True, capture_output=True, text=True).stdout
    m = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", out, re.M)
    if not m:
        raise PackagingError(f"cannot read the package line of {apk}")
    return {"package": m.group(1), "versionCode": m.group(2), "versionName": m.group(3)}


def parse_apksigner_cert(stdout: str) -> str | None:
    """`apksigner verify --print-certs` 输出里第一个签名者的证书 SHA-256。

    格式随 build-tools 版本变过：旧的是 "Signer #1 certificate SHA-256 digest: <hex>"，
    build-tools 37 起是 "V2 Signer: certificate SHA-256 digest: <hex>"（V1/V2/V3/V4 各一组）。两种都认。
    """
    m = re.search(r"(?:Signer #1|V\d+ Signer):? certificate SHA-256 digest: ([0-9a-f]{64})", stdout)
    return m.group(1) if m else None


def apk_cert_sha256(apk: Path) -> str | None:
    tool = build_tool("apksigner")
    r = subprocess.run([str(tool), "verify", "--print-certs", str(apk)], capture_output=True, text=True)
    # 不是 None 就是“未签名”：先把 apksigner 自己的说法打出来，否则只能看到一句“未签名”而不知道为什么
    if r.returncode != 0:
        log(f"apksigner verify failed (rc={r.returncode}, {tool}): {(r.stderr or r.stdout).strip()[:800]}")
        return None
    cert = parse_apksigner_cert(r.stdout)
    if cert is None:
        log(f"apksigner verify printed no certificate SHA-256 digest that we can parse ({tool}): {r.stdout.strip()[:800]}")
    return cert


# ---------------------------------------------------------------------------------------- build

def build_pi() -> None:
    build_mjs = PI_DIR / "build.mjs"
    if not build_mjs.is_file():
        log("WARNING: core/pi-runtime/build.mjs does not exist yet (W3); the APK has no pi-agent.js")
        return
    if not (PI_DIR / "node_modules").is_dir():
        run(["npm", "ci", "--prefer-offline", "--no-audit", "--no-fund"], cwd=PI_DIR)
    run(["node", "build.mjs"], cwd=PI_DIR)
    for name in ("pi-agent.js", "model-catalog.json"):
        if not (ASSETS / name).is_file():
            raise PackagingError(f"build.mjs did not produce app/src/main/assets/{name}")


def build_apks(variant: str, with_runner: bool, extra: list[str]) -> None:
    task = "assemble" + variant.capitalize()
    tasks = [f":app:{task}"] + ([f":runner:{task}"] if with_runner else [])
    run([str(ROOT / "gradlew"), *tasks, "-Pagentos.skipPiBundle=true", *extra], cwd=ROOT)


def find_apk(module: str, variant: str) -> Path:
    d = ROOT / module / "build" / "outputs" / "apk" / variant
    for name in (f"{module}-{variant}.apk", f"{module}-{variant}-unsigned.apk"):
        if (d / name).is_file():
            return d / name
    raise PackagingError(f"no APK in {d}")


# ---------------------------------------------------------------------------------------- checks

def parse_module_prop(text: str) -> dict[str, str]:
    props = {}
    for line in text.splitlines():
        if line and "=" in line:
            k, v = line.split("=", 1)
            props[k] = v
    return props


MATRIX_LINE = re.compile(r"^(#.*|\s*|[a-z_]+:( .*)?|  - \S.*)$")


def parse_matrix(text: str) -> tuple[dict[str, str], dict[str, list[str]]]:
    """The flat subset module/common.sh understands; anything else is an error."""
    scalars: dict[str, str] = {}
    lists: dict[str, list[str]] = {}
    current = None
    for n, line in enumerate(text.splitlines(), 1):
        if not MATRIX_LINE.match(line):
            raise PackagingError(f"support-matrix.yaml:{n}: outside the supported flat subset: {line!r}")
        if not line.strip() or line.startswith("#"):
            continue
        if line.startswith("  - "):
            if current is None:
                raise PackagingError(f"support-matrix.yaml:{n}: list item without a key")
            item = line[4:].split(" #", 1)[0].strip().strip("'\"")
            if " " in item:
                raise PackagingError(f"support-matrix.yaml:{n}: list items must not contain spaces")
            lists[current].append(item)
            continue
        key, _, value = line.partition(":")
        value = value.split(" #", 1)[0].strip().strip("'\"")
        if value:
            scalars[key] = value
            current = None
        else:
            lists[key] = []
            current = key
    return scalars, lists


def shell_syntax(name: str, data: bytes) -> list[str]:
    errors = []
    shells = [s for s in ("dash", "sh") if shutil.which(s)]
    with tempfile.NamedTemporaryFile(suffix=".sh") as f:
        f.write(data)
        f.flush()
        for sh in shells:
            r = subprocess.run([sh, "-n", f.name], capture_output=True, text=True)
            if r.returncode != 0:
                errors.append(f"{name}: `{sh} -n` failed: {r.stderr.strip()}")
    return errors


def check_zip(path: Path, expect_signed: bool = True) -> list[str]:
    """Offline checks on a built zip. Returns the list of errors (empty = OK)."""
    errors: list[str] = []
    with zipfile.ZipFile(path) as z:
        names = [i.filename for i in z.infolist() if not i.is_dir()]
        files = {n: z.read(n) for n in names}

    for n in names:
        for pattern, why in FORBIDDEN:
            if pattern.search(n):
                errors.append(f"{n}: forbidden ({why})")
        if not any(p.match(n) for p in ALLOWED):
            errors.append(f"{n}: not in the allowlist (only scripts, module.prop, the support matrix and the APKs)")
        data = files[n]
        if not n.endswith(".apk"):
            if data[:4] == b"\x7fELF":
                errors.append(f"{n}: native binary (the module ships no binaries)")
            try:
                data.decode("utf-8")
            except UnicodeDecodeError:
                errors.append(f"{n}: not UTF-8 text")
            if b"\r\n" in data:
                errors.append(f"{n}: CRLF line endings")
        if EXECUTABLE.search(n):
            errors.extend(shell_syntax(n, data))

    for required in MODULE_FILES + ["module.prop", "app/apks.list"]:
        if required not in files:
            errors.append(f"{required}: missing")
    if errors and any(e.endswith(": missing") for e in errors):
        return errors

    if not files["META-INF/com/google/android/update-binary"].startswith(b"#!/sbin/sh"):
        errors.append("update-binary: must start with #!/sbin/sh")
    if files["META-INF/com/google/android/updater-script"].strip() != b"#MAGISK":
        errors.append("updater-script: must be #MAGISK")

    prop = parse_module_prop(files["module.prop"].decode("utf-8"))
    for key in ("id", "name", "version", "versionCode", "author", "description"):
        if not prop.get(key):
            errors.append(f"module.prop: missing {key}")
    if prop.get("id") != MODULE_ID:
        errors.append(f"module.prop: id must be {MODULE_ID}")
    if not re.fullmatch(r"\d+", prop.get("versionCode", "")):
        errors.append("module.prop: versionCode must be an integer")
    if not re.fullmatch(r"[A-Za-z0-9._-]+", prop.get("version", "")):
        errors.append("module.prop: version may only contain A-Za-z0-9._- (service.sh reports it)")
    if "@" in files["module.prop"].decode("utf-8"):
        errors.append("module.prop: unrendered template placeholder")

    try:
        scalars, lists = parse_matrix(files["support-matrix.yaml"].decode("utf-8"))
        for key in ("schema", "matrix_version", "api_min", "api_max", "magisk_min_version_code",
                    "kernelsu_min_version_code", "min_free_mb"):
            if key not in scalars:
                errors.append(f"support-matrix.yaml: missing {key}")
        for key in ("api_min", "api_max", "magisk_min_version_code", "kernelsu_min_version_code", "min_free_mb"):
            if key in scalars and not scalars[key].isdigit():
                errors.append(f"support-matrix.yaml: {key} must be an integer")
        if scalars.get("api_min", "0").isdigit() and scalars.get("api_max", "0").isdigit() and \
                int(scalars["api_min"]) > int(scalars["api_max"]):
            errors.append("support-matrix.yaml: api_min > api_max")
        for key in ("verified_fingerprints", "conflicting_modules"):
            if key not in lists:
                errors.append(f"support-matrix.yaml: missing list {key}")
    except PackagingError as e:
        errors.append(str(e))

    listed = set()
    main_seen = False
    for n, line in enumerate(files["app/apks.list"].decode("utf-8").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split(" ")
        if len(parts) != 5:
            errors.append(f"app/apks.list:{n}: expected 5 fields")
            continue
        pkg, vc, rel, sha, cert = parts
        listed.add(rel)
        main_seen |= pkg == APP_PACKAGE
        if pkg not in (APP_PACKAGE, RUNNER_PACKAGE):
            errors.append(f"app/apks.list:{n}: unexpected package {pkg}")
        if not vc.isdigit():
            errors.append(f"app/apks.list:{n}: versionCode must be an integer")
        if rel not in files:
            errors.append(f"app/apks.list:{n}: {rel} is not in the zip")
        elif sha256_bytes(files[rel]) != sha:
            errors.append(f"app/apks.list:{n}: SHA-256 of {rel} does not match")
        if expect_signed and not re.fullmatch(r"[0-9a-f]{64}", cert):
            errors.append(f"app/apks.list:{n}: {pkg} is not signed (pm cannot install it)")
    if not main_seen:
        errors.append(f"app/apks.list: {APP_PACKAGE} is missing")
    for n in names:
        if n.endswith(".apk") and n not in listed:
            errors.append(f"{n}: APK not listed in app/apks.list")
    return errors


# ---------------------------------------------------------------------------------------- zip

def source_epoch() -> int:
    if os.environ.get("SOURCE_DATE_EPOCH", "").isdigit():
        return int(os.environ["SOURCE_DATE_EPOCH"])
    r = subprocess.run(["git", "log", "-1", "--format=%ct"], cwd=ROOT, capture_output=True, text=True)
    return int(r.stdout.strip()) if r.returncode == 0 and r.stdout.strip().isdigit() else 315532800


def write_zip(entries: dict[str, bytes], dest: Path, epoch: int) -> None:
    stamp = time.gmtime(max(epoch, 315532800))[:6]  # zip cannot store dates before 1980
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, date_time=stamp)
            info.create_system = 3  # unix, so external_attr carries the mode
            mode = 0o755 if EXECUTABLE.search(name) else 0o644
            info.external_attr = (0o100000 | mode) << 16
            info.compress_type = zipfile.ZIP_STORED if name.endswith(".apk") else zipfile.ZIP_DEFLATED
            z.writestr(info, entries[name])
    dest.write_bytes(buf.getvalue())


def stage(version: str, version_code: str, apks: list[tuple[str, Path]], allow_unsigned: bool) -> tuple[dict[str, bytes], list[dict]]:
    entries: dict[str, bytes] = {}
    for rel in MODULE_FILES:
        entries[rel] = (MODULE_SRC / rel).read_bytes()
    for rel in OPTIONAL_MODULE_FILES:
        if (MODULE_SRC / rel).is_file():
            entries[rel] = (MODULE_SRC / rel).read_bytes()
    template = (MODULE_SRC / "module.prop.template").read_text(encoding="utf-8")
    entries["module.prop"] = template.replace("@VERSION@", version).replace("@VERSION_CODE@", version_code).encode()

    lines = ["# <package> <versionCode> <path> <APK SHA-256> <signing cert SHA-256>  (tools/package-module.py)"]
    info = []
    for kind, apk in apks:
        badging = apk_badging(apk)
        expected = APP_PACKAGE if kind == "app" else RUNNER_PACKAGE
        if badging["package"] != expected:
            raise PackagingError(f"{apk}: package {badging['package']}, expected {expected}")
        cert = apk_cert_sha256(apk)
        if cert is None and not allow_unsigned:
            raise PackagingError(f"{apk} is not signed; pm cannot install it. Set AGENTOS_SIGNING_* "
                                 "(docs/development.md) or use --variant debug; --allow-unsigned only for offline checks")
        rel = f"app/AgentOS-{version}.apk" if kind == "app" else f"app/AgentOS-Runner-{version}.apk"
        data = apk.read_bytes()
        entries[rel] = data
        sha = sha256_bytes(data)
        lines.append(f"{expected} {badging['versionCode']} {rel} {sha} {cert or '-'}")
        if badging["versionName"] != version:
            log(f"WARNING: {apk.name} versionName {badging['versionName']} != module version {version}")
        info.append({"package": expected, "versionCode": int(badging["versionCode"]),
                     "versionName": badging["versionName"], "path": rel, "sha256": sha, "certSha256": cert})
    entries["app/apks.list"] = ("\n".join(lines) + "\n").encode()
    return entries, info


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--check", type=Path, help="only run the offline checks on an existing zip")
    ap.add_argument("--variant", choices=("release", "debug"), default="release")
    ap.add_argument("--with-runner", action="store_true", help="also package the Runner APK (W21)")
    ap.add_argument("--skip-pi", action="store_true", help="do not run core/pi-runtime/build.mjs")
    ap.add_argument("--skip-gradle", action="store_true", help="use the APKs already built")
    ap.add_argument("--app-apk", type=Path, help="package this AgentOS App APK instead of the Gradle output")
    ap.add_argument("--runner-apk", type=Path, help="package this Runner APK (implies --with-runner)")
    ap.add_argument("--allow-unsigned", action="store_true", help="offline checks only: accept unsigned APKs")
    ap.add_argument("--gradle-arg", action="append", default=[], help="extra argument for gradlew (repeatable)")
    ap.add_argument("--out", type=Path, default=ROOT / "build" / "module")
    args = ap.parse_args()

    try:
        if args.check:
            errors = check_zip(args.check, expect_signed=not args.allow_unsigned)
            for e in errors:
                print(f"ERROR {e}")
            print(f"{args.check}: {'OK' if not errors else f'{len(errors)} error(s)'}")
            return 1 if errors else 0

        props = gradle_properties()
        version, version_code = props["agentos.version"], props["agentos.versionCode"]
        with_runner = args.with_runner or args.runner_apk is not None

        if not args.skip_pi and not args.app_apk:
            build_pi()
        if not args.skip_gradle and not args.app_apk:
            build_apks(args.variant, with_runner and not args.runner_apk, args.gradle_arg)

        apks = [("app", args.app_apk or find_apk("app", args.variant))]
        if with_runner:
            apks.append(("runner", args.runner_apk or find_apk("runner", args.variant)))

        entries, info = stage(version, version_code, apks, args.allow_unsigned)
        args.out.mkdir(parents=True, exist_ok=True)
        unsigned = any(i["certSha256"] is None for i in info)
        dest = args.out / f"agentos-{version}{'-unsigned' if unsigned else ''}.zip"
        write_zip(entries, dest, source_epoch())

        errors = check_zip(dest, expect_signed=not unsigned)
        if errors:
            for e in errors:
                print(f"ERROR {e}")
            raise PackagingError(f"{dest.name} failed the offline checks")

        digest = sha256_file(dest)
        (args.out / (dest.name + ".sha256")).write_text(f"{digest}  {dest.name}\n")
        rev = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
        pi = ASSETS / "pi-agent.js"
        (args.out / "build-info.json").write_text(json.dumps({
            "zip": dest.name, "sha256": digest, "moduleVersion": version, "moduleVersionCode": int(version_code),
            "variant": "custom" if args.app_apk else args.variant, "gitRevision": rev,
            "piAgentSha256": sha256_file(pi) if pi.is_file() else None, "apks": info,
        }, indent=2, ensure_ascii=False) + "\n")
        log(f"OK {dest} ({dest.stat().st_size} bytes) sha256={digest}")
        for i in info:
            log(f"   {i['package']} v{i['versionCode']} ({i['versionName']}) cert={i['certSha256'] or 'UNSIGNED'}")
        return 0
    except (PackagingError, subprocess.CalledProcessError) as e:
        print(f"[package-module] ERROR: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
