"""tools/package-release.py: the pure parts (SHA256SUMS text, the folder check). No Gradle, no APKs.
Run: python3 -m unittest discover -s tools/tests -p 'test_package_release.py' -v
"""
import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("package_release", HERE.parent / "package-release.py")
pr = importlib.util.module_from_spec(spec)
sys.modules["package_release"] = pr
spec.loader.exec_module(pr)

CERT = "ab" * 32
SAMPLES = ("alarm", "todo")


def make_folder(root: Path, version="0.1.0", unsigned=False, samples=SAMPLES, certs=None, variant="release"):
    suffix = "-unsigned" if unsigned else ""
    with zipfile.ZipFile(root / f"agentos-{version}{suffix}.zip", "w") as z:
        z.writestr("module.prop", f"id=agentos\nversion={version}\n")
        for n in ("common.sh", "support-matrix.yaml", "app/apks.list", "customize.sh", "service.sh"):
            z.writestr(n, "x\n")
    for s in samples:
        (root / f"AgentOS-Sample-{s}-{version}{suffix}.apk").write_bytes(s.encode() * 10)
    for n in ("install.sh", "check-device.sh", "INSTALL.md"):
        (root / n).write_text("# 安装 / Installing\n")
    (root / "RELEASE-NOTES.md").write_text("## 已知限制\n- x\n\n## Known limitations\n- x\n")
    certs = certs or {s: CERT for s in samples}
    info = {"version": version, "variant": variant, "dryRun": unsigned, "samples": list(samples), "moduleAppCertSha256": CERT,
            "sampleApks": [{"sample": s, "signingCertSha256": certs.get(s)} for s in samples]}
    (root / "build-info.json").write_text(json.dumps(info))
    entries = [(p.name, pr.sha256_file(p)) for p in root.iterdir() if p.name not in ("SHA256SUMS", "build-info.json")]
    (root / "SHA256SUMS").write_text(pr.sums_text(entries))


class Sums(unittest.TestCase):
    def test_format_is_sha256sum_compatible_and_sorted(self):
        t = pr.sums_text([("b.apk", "2" * 64), ("a.zip", "1" * 64)])
        self.assertEqual(t, "1" * 64 + "  a.zip\n" + "2" * 64 + "  b.apk\n")

    def test_parse_roundtrip_and_binary_marker(self):
        t = pr.sums_text([("a.zip", "1" * 64)]) + "2" * 64 + " *b.apk\n" + "garbage line\n"
        self.assertEqual(pr.parse_sums(t), {"a.zip": "1" * 64, "b.apk": "2" * 64})

    def test_expected_names(self):
        n = pr.expected_names("0.1.0", ("alarm",), False)
        self.assertIn("agentos-0.1.0.zip", n)
        self.assertIn("AgentOS-Sample-alarm-0.1.0.apk", n)
        self.assertIn("install.sh", n)
        n = pr.expected_names("0.1.0", ("alarm",), True)
        self.assertIn("agentos-0.1.0-unsigned.zip", n)
        self.assertIn("AgentOS-Sample-alarm-0.1.0-unsigned.apk", n)


class Folder(unittest.TestCase):
    def setUp(self):
        self._t = tempfile.TemporaryDirectory()
        self.addCleanup(self._t.cleanup)
        self.root = Path(self._t.name)

    def test_a_good_folder_has_no_problems(self):
        make_folder(self.root)
        self.assertEqual(pr.check_folder(self.root), [])

    def test_an_unsigned_dry_run_folder_has_no_problems_either(self):
        make_folder(self.root, unsigned=True, certs={s: None for s in SAMPLES})
        self.assertEqual(pr.check_folder(self.root), [])

    def test_missing_unexpected_and_tampered_files(self):
        make_folder(self.root)
        (self.root / "install.sh").unlink()
        (self.root / "extra.txt").write_text("x")
        (self.root / "AgentOS-Sample-todo-0.1.0.apk").write_bytes(b"changed")
        text = "\n".join(pr.check_folder(self.root))
        self.assertIn("missing file: install.sh", text)
        self.assertIn("unexpected file: extra.txt", text)
        self.assertIn("AgentOS-Sample-todo-0.1.0.apk: SHA-256 differs", text)
        self.assertIn("extra.txt is not listed", text)

    def test_sums_listing_a_file_that_is_not_there(self):
        make_folder(self.root)
        with (self.root / "SHA256SUMS").open("a") as f:
            f.write("0" * 64 + "  ghost.apk\n")
        self.assertIn("lists ghost.apk which is not in the folder", "\n".join(pr.check_folder(self.root)))

    def test_a_release_with_mixed_certificates(self):
        make_folder(self.root, certs={"alarm": CERT, "todo": "cd" * 32})
        self.assertIn("signing certificates differ", "\n".join(pr.check_folder(self.root)))

    def test_a_release_with_an_unsigned_apk(self):
        make_folder(self.root, certs={"alarm": CERT, "todo": None})
        self.assertIn("signing certificates differ", "\n".join(pr.check_folder(self.root)))

    def test_zip_version_must_match(self):
        make_folder(self.root)
        with zipfile.ZipFile(self.root / "agentos-0.1.0.zip", "w") as z:
            z.writestr("module.prop", "id=agentos\nversion=0.0.9\n")
            for n in ("common.sh", "support-matrix.yaml", "app/apks.list", "customize.sh", "service.sh"):
                z.writestr(n, "x\n")
        entries = [(p.name, pr.sha256_file(p)) for p in self.root.iterdir() if p.name not in ("SHA256SUMS", "build-info.json")]
        (self.root / "SHA256SUMS").write_text(pr.sums_text(entries))
        self.assertIn("module.prop version 0.0.9 != 0.1.0", "\n".join(pr.check_folder(self.root)))

    def test_zip_must_carry_what_install_sh_reads(self):
        make_folder(self.root)
        with zipfile.ZipFile(self.root / "agentos-0.1.0.zip", "w") as z:
            z.writestr("module.prop", "id=agentos\nversion=0.1.0\n")
        entries = [(p.name, pr.sha256_file(p)) for p in self.root.iterdir() if p.name not in ("SHA256SUMS", "build-info.json")]
        (self.root / "SHA256SUMS").write_text(pr.sums_text(entries))
        text = "\n".join(pr.check_folder(self.root))
        for need in ("common.sh", "support-matrix.yaml", "app/apks.list"):
            self.assertIn(f"{need} is missing", text)

    def test_release_notes_need_both_languages(self):
        make_folder(self.root)
        (self.root / "RELEASE-NOTES.md").write_text("## Known limitations\n- x\n")
        entries = [(p.name, pr.sha256_file(p)) for p in self.root.iterdir() if p.name not in ("SHA256SUMS", "build-info.json")]
        (self.root / "SHA256SUMS").write_text(pr.sums_text(entries))
        self.assertIn("已知限制", "\n".join(pr.check_folder(self.root)))

    def test_replacement_character_in_the_docs(self):
        make_folder(self.root)
        (self.root / "INSTALL.md").write_text("坏字符 \ufffd\n")
        entries = [(p.name, pr.sha256_file(p)) for p in self.root.iterdir() if p.name not in ("SHA256SUMS", "build-info.json")]
        (self.root / "SHA256SUMS").write_text(pr.sums_text(entries))
        self.assertIn("U+FFFD", "\n".join(pr.check_folder(self.root)))

    def test_empty_folder(self):
        self.assertIn("no build-info.json", pr.check_folder(self.root)[0])

    def test_a_folder_the_installer_would_accept(self):
        # the contract between the two scripts: every name install.sh globs for is what package-release.py produces
        make_folder(self.root)
        names = {p.name for p in self.root.iterdir()}
        self.assertIn("agentos-0.1.0.zip", names)
        self.assertTrue(any(n.startswith("AgentOS-Sample-alarm-") and n.endswith(".apk") for n in names))
        script = (HERE.parent / "install.sh").read_text()
        self.assertIn('"$DIR"/agentos-*.zip', script)
        self.assertIn('"$DIR"/AgentOS-Sample-"$s"-*.apk', script)


if __name__ == "__main__":
    unittest.main()
