#!/usr/bin/env python3
"""Build, check and collect the release APKs of the sample apps (docs/next-apps-plan.md 7.4).

    python3 tools/package-samples.py [--out DIR] [--only alarm,todo] [--skip-gradle] [--allow-unsigned]
                                     [--app-apk PATH] [--expect-cert SHA256] [--notes FILE] [--skip-conformance]

For every `plugins/samples/<name>` that has a build.gradle.kts:

  1. Gradle `:plugins:samples:<name>:assembleRelease` (signed from AGENTOS_SIGNING_* by the root build.gradle.kts; README "项目发布证书").
  2. `apksigner verify --print-certs`: the signing certificate must equal the AgentOS App's (--expect-cert, or read from --app-apk,
     default app/build/outputs/apk/release/app-release.apk). An unsigned APK is refused unless --allow-unsigned (offline dry run:
     everything is written with an `-unsigned` suffix and build-info.json says `"signed": false`; never a release asset).
     The debug certificate and `releaseTest` builds can never match, so they cannot be shipped by accident.
  3. `aapt2 dump badging`: package == applicationId, versionName/versionCode == gradle.properties (agentos.version, agentos.versionCode),
     the locales include `en`, uses-permission == tools/package-samples.permissions.json for that app (a new permission needs an
     edit of that file in the same commit; the generated DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION is ignored).
  4. The merged manifest and the dex files hold no debug self-test receivers (components under `<applicationId>.debug.`, dex type
     descriptors `L<applicationId path>/debug/`).
  5. The plugin package inside the APK: assets/agent-plugin/plugin.json (name == directory name, a displayName, mcpServers) and a
     skills/<name>/SKILL.md. The full ManifestReader check is the Gradle test SamplePluginsConformanceTest (run unless --skip-conformance).
  6. tools/check-i18n.py.
  7. Output in --out (default dist-samples/): AgentOS-Sample-<name>-<ver>.apk, SHA256SUMS (sha256sum -c format), build-info.json
     (per APK: SHA-256, signing certificate SHA-256, versionCode, versionName, locales, permissions, git commit) and RELEASE-NOTES.md
     (copied from --notes or docs/release-notes/<ver>.md; must have a Chinese part "已知限制" and an English part "Known limitations").
     APKs are not promised to be byte-reproducible.

Secrets are never read or printed here; Gradle reads the signing environment.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HERE = Path(__file__).resolve().parent

_spec = importlib.util.spec_from_file_location("package_module", HERE / "package-module.py")
_pm = importlib.util.module_from_spec(_spec)
sys.modules["package_module"] = _pm
_spec.loader.exec_module(_pm)  # reuse the SDK/hash helpers; its main() is guarded

PackagingError = _pm.PackagingError
PERMISSIONS_FILE = HERE / "package-samples.permissions.json"
DEFAULT_APP_APK = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
DYNAMIC_PERMISSION_SUFFIX = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
CJK = re.compile("[\u4e00-\u9fff]")


def log(msg: str) -> None:
    print(f"[package-samples] {msg}", flush=True)


# ------------------------------------------------------------------------------------------ pure helpers (unit tested)

def discover_samples(root: Path) -> list[dict]:
    out = []
    base = root / "plugins" / "samples"
    for d in sorted(p for p in base.iterdir() if p.is_dir()):
        gradle = d / "build.gradle.kts"
        if not gradle.is_file():
            continue
        m = re.search(r'applicationId\s*=\s*"([^"]+)"', gradle.read_text(encoding="utf-8"))
        if not m:
            raise PackagingError(f"{gradle}: no applicationId")
        out.append({"name": d.name, "dir": d, "application_id": m.group(1)})
    return out


def load_permissions(path: Path) -> dict[str, set[str]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    return {k: set(v) for k, v in data.items() if not k.startswith("_")}


def parse_badging(text: str) -> dict:
    m = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", text, re.M)
    if not m:
        raise PackagingError("cannot read the package line of aapt2 badging")
    locales = re.search(r"^locales:(.*)$", text, re.M)
    return {
        "package": m.group(1),
        "versionCode": m.group(2),
        "versionName": m.group(3),
        "permissions": re.findall(r"^uses-permission(?:-sdk-23)?: name='([^']+)'", text, re.M),
        "locales": re.findall(r"'([^']*)'", locales.group(1)) if locales else [],
        "minSdk": (re.search(r"^sdkVersion:'(\d+)'", text, re.M) or [None, None])[1],
        "targetSdk": (re.search(r"^targetSdkVersion:'(\d+)'", text, re.M) or [None, None])[1],
    }


def check_badging(sample: dict, badging: dict, version: str, version_code: str, allowed: set[str] | None) -> list[str]:
    name, problems = sample["name"], []
    if badging["package"] != sample["application_id"]:
        problems.append(f"{name}: package is {badging['package']}, build.gradle.kts says {sample['application_id']}")
    if badging["versionName"] != version:
        problems.append(f"{name}: versionName {badging['versionName']} != agentos.version {version}")
    if badging["versionCode"] != version_code:
        problems.append(f"{name}: versionCode {badging['versionCode']} != agentos.versionCode {version_code}")
    if "en" not in badging["locales"]:
        problems.append(f"{name}: the APK has no `en` resources (locales: {badging['locales'][:6]}...)")
    perms = {p for p in badging["permissions"] if not p.endswith(DYNAMIC_PERMISSION_SUFFIX)}
    if allowed is None:
        problems.append(f"{name}: no entry in {PERMISSIONS_FILE.name}; add the permissions it may request: {sorted(perms)}")
    else:
        for p in sorted(perms - allowed):
            problems.append(f"{name}: requests {p} which is not in {PERMISSIONS_FILE.name} (a new permission needs that file edited in the same commit)")
        for p in sorted(allowed - perms):
            problems.append(f"{name}: {PERMISSIONS_FILE.name} lists {p} but the APK does not request it (remove it from the file)")
    return problems


def manifest_components(xmltree: str) -> list[str]:
    """Component class names in `aapt2 dump xmltree --file AndroidManifest.xml` output (activity, service, receiver, provider)."""
    names, current = [], False
    for line in xmltree.splitlines():
        m = re.match(r"\s*E: (\S+)", line)
        if m:
            current = m.group(1) in ("activity", "activity-alias", "service", "receiver", "provider")
            continue
        if current:
            n = re.search(r'android:name\(0x01010003\)="([^"]+)"', line)
            if n:
                names.append(n.group(1))
                current = False
    return names


def check_no_debug_components(sample: dict, components: list[str]) -> list[str]:
    prefix = sample["application_id"] + ".debug."
    return [f"{sample['name']}: release manifest has a debug component {c}" for c in components if c.startswith(prefix)]


def dex_has_debug_classes(dex_blobs: list[bytes], application_id: str) -> bool:
    needle = ("L" + application_id.replace(".", "/") + "/debug/").encode()
    return any(needle in blob for blob in dex_blobs)


def check_plugin_files(sample: dict, names: set[str], plugin_json: dict | None) -> list[str]:
    name, problems = sample["name"], []
    if plugin_json is None:
        return [f"{name}: assets/agent-plugin/plugin.json is missing from the APK"]
    if plugin_json.get("name") != name:
        problems.append(f"{name}: plugin.json name is {plugin_json.get('name')!r}, expected {name!r}")
    ext = (plugin_json.get("extensions") or {})
    if not (ext.get("com.openai", {}).get("interface", {}) or {}).get("displayName"):
        problems.append(f"{name}: plugin.json has no extensions.\"com.openai\".interface.displayName")
    if not (ext.get("org.agentos", {}).get("mcpServers")):
        problems.append(f"{name}: plugin.json has no extensions.\"org.agentos\".mcpServers")
    if not any(re.fullmatch(r"assets/agent-plugin/skills/[^/]+/SKILL\.md", n) for n in names):
        problems.append(f"{name}: no assets/agent-plugin/skills/<name>/SKILL.md in the APK")
    return problems


def check_release_notes(text: str) -> list[str]:
    problems = []
    if "已知限制" not in text or not CJK.search(text):
        problems.append("release notes: the Chinese part with a 已知限制 section is missing")
    if not re.search(r"known limitations", text, re.I):
        problems.append("release notes: the English part with a 'Known limitations' section is missing")
    return problems


def sums_text(entries: list[tuple[str, str]]) -> str:
    """sha256sum -c format: '<hex>  <file>' (two spaces)."""
    return "".join(f"{digest}  {name}\n" for name, digest in entries)


# ------------------------------------------------------------------------------------------ build and collect

def run(cmd: list[str], cwd: Path | None = None) -> None:
    log("$ " + " ".join(cmd))
    subprocess.run(cmd, cwd=cwd, check=True)


def find_release_apk(sample: dict) -> tuple[Path, bool]:
    d = sample["dir"] / "build" / "outputs" / "apk" / "release"
    for name, signed in ((f"{sample['name']}-release.apk", True), (f"{sample['name']}-release-unsigned.apk", False)):
        if (d / name).is_file():
            return d / name, signed
    raise PackagingError(f"{sample['name']}: no release APK in {d}")


def git_commit() -> str:
    r = subprocess.run(["git", "rev-parse", "--short=12", "HEAD"], cwd=ROOT, capture_output=True, text=True)
    return r.stdout.strip() if r.returncode == 0 else "unknown"


def inspect_apk(sample: dict, apk: Path) -> tuple[dict, list[str], list[str], list[str]]:
    """Badging, manifest component names, and (problems from dex / plugin files)."""
    aapt2 = str(_pm.build_tool("aapt2"))
    badging = parse_badging(subprocess.run([aapt2, "dump", "badging", str(apk)], check=True, capture_output=True, text=True).stdout)
    tree = subprocess.run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)], check=True, capture_output=True, text=True).stdout
    components = manifest_components(tree)
    problems = check_no_debug_components(sample, components)
    with zipfile.ZipFile(apk) as z:
        names = set(z.namelist())
        dex = [z.read(n) for n in sorted(names) if re.fullmatch(r"classes\d*\.dex", n)]
        raw = z.read("assets/agent-plugin/plugin.json") if "assets/agent-plugin/plugin.json" in names else None
    if dex_has_debug_classes(dex, sample["application_id"]):
        problems.append(f"{sample['name']}: the dex files contain classes under {sample['application_id']}.debug (self-test code in a release build)")
    plugin = json.loads(raw) if raw else None
    plugin_problems = check_plugin_files(sample, names, plugin)
    return badging, components, problems, plugin_problems


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=str(ROOT / "dist-samples"))
    ap.add_argument("--only", help="comma separated sample names")
    ap.add_argument("--skip-gradle", action="store_true", help="use the APKs that are already built")
    ap.add_argument("--allow-unsigned", action="store_true", help="offline dry run with unsigned APKs (never a release)")
    ap.add_argument("--app-apk", default=str(DEFAULT_APP_APK), help="the AgentOS App release APK whose certificate is the reference")
    ap.add_argument("--expect-cert", help="the reference certificate SHA-256 (instead of reading --app-apk)")
    ap.add_argument("--notes", help="bilingual release notes (default docs/release-notes/<version>.md)")
    ap.add_argument("--skip-conformance", action="store_true", help="skip the SamplePluginsConformanceTest Gradle run")
    args = ap.parse_args()

    props = _pm.gradle_properties()
    version, version_code = props["agentos.version"], props["agentos.versionCode"]
    samples = discover_samples(ROOT)
    if args.only:
        wanted = set(args.only.split(","))
        unknown = wanted - {s["name"] for s in samples}
        if unknown:
            raise PackagingError(f"unknown sample(s): {sorted(unknown)}")
        samples = [s for s in samples if s["name"] in wanted]
    permissions = load_permissions(PERMISSIONS_FILE)
    gradle_extra = ["-Pagentos.skipPiBundle=true"]

    if not args.skip_gradle:
        run([str(ROOT / "gradlew"), *[f":plugins:samples:{s['name']}:assembleRelease" for s in samples], *gradle_extra], cwd=ROOT)
    problems: list[str] = []

    expect_cert = args.expect_cert
    if not expect_cert and not args.allow_unsigned:
        app_apk = Path(args.app_apk)
        if not app_apk.is_file():
            raise PackagingError(f"{app_apk} does not exist; build it (:app:assembleRelease) or pass --expect-cert")
        expect_cert = _pm.apk_cert_sha256(app_apk)
        if not expect_cert:
            raise PackagingError(f"{app_apk} is not signed; the reference certificate is unknown")

    collected = []
    for s in samples:
        apk, signed = find_release_apk(s)
        cert = _pm.apk_cert_sha256(apk) if signed else None
        if not signed or cert is None:
            if not args.allow_unsigned:
                problems.append(f"{s['name']}: {apk.name} is not signed (set AGENTOS_SIGNING_*; --allow-unsigned is only for offline dry runs)")
        elif expect_cert and cert != expect_cert:
            problems.append(f"{s['name']}: signing certificate {cert[:16]}... differs from the AgentOS App's {expect_cert[:16]}...")
        badging, _components, debug_problems, plugin_problems = inspect_apk(s, apk)
        problems += check_badging(s, badging, version, version_code, permissions.get(s["name"]))
        problems += debug_problems + plugin_problems
        collected.append((s, apk, badging, cert))
        log(f"{s['name']}: {apk.name} {'signed' if cert else 'UNSIGNED'}; {len(badging['permissions'])} permission lines, {len(badging['locales'])} locales")

    if not args.skip_conformance and not args.skip_gradle:
        r = subprocess.run([str(ROOT / "gradlew"), ":core:extensions:test", "--tests", "*SamplePluginsConformanceTest*", *gradle_extra], cwd=ROOT)
        if r.returncode != 0:
            problems.append("SamplePluginsConformanceTest failed")
    r = subprocess.run([sys.executable, str(HERE / "check-i18n.py")], cwd=ROOT)
    if r.returncode != 0:
        problems.append("tools/check-i18n.py failed")

    notes_path = Path(args.notes) if args.notes else ROOT / "docs" / "release-notes" / f"{version}.md"
    notes = None
    if notes_path.is_file():
        notes = notes_path.read_text(encoding="utf-8")
        problems += check_release_notes(notes)
    else:
        problems.append(f"release notes not found: {notes_path}")

    if problems:
        log("FAILED:")
        for p in problems:
            print("  - " + p)
        return 1

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    suffix = "" if all(c[3] for c in collected) else "-unsigned"
    sums, info = [], []
    for s, apk, badging, cert in collected:
        name = f"AgentOS-Sample-{s['name']}-{version}{suffix}.apk"
        (out / name).write_bytes(apk.read_bytes())
        digest = _pm.sha256_file(out / name)
        sums.append((name, digest))
        info.append({
            "file": name, "sample": s["name"], "package": badging["package"], "sha256": digest, "signed": cert is not None,
            "signingCertSha256": cert, "versionName": badging["versionName"], "versionCode": int(badging["versionCode"]),
            "minSdk": badging["minSdk"], "targetSdk": badging["targetSdk"],
            "languages": sorted({loc for loc in badging["locales"] if loc.split("-")[0] in ("zh", "en") or loc == "--_--"}),
            "permissions": sorted(p for p in badging["permissions"] if not p.endswith(DYNAMIC_PERMISSION_SUFFIX)),
        })
    (out / "SHA256SUMS").write_text(sums_text(sums), encoding="utf-8")
    (out / "build-info.json").write_text(json.dumps({"version": version, "commit": git_commit(), "dryRun": bool(suffix), "apks": info}, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (out / "RELEASE-NOTES.md").write_text(notes or "", encoding="utf-8")
    log(f"wrote {len(sums)} APK(s), SHA256SUMS, build-info.json, RELEASE-NOTES.md to {out}" + (" (UNSIGNED DRY RUN: not a release)" if suffix else ""))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except PackagingError as e:
        print(f"[package-samples] error: {e}", file=sys.stderr)
        sys.exit(2)
