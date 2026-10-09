"""tools/install.sh against a fake `adb` (no phone needed).

The fake adb (written to a temp folder that goes first on PATH) answers the handful of commands install.sh sends and logs every call, so the
tests can assert what the script did and did not do to the "phone": which manager's flash command ran, that a reboot only happens after a good
flash, that nothing is pushed when a checksum is wrong, that every call after `devices` carries `-s <serial>`, and so on.

What this does NOT prove: that Magisk / KernelSU accept the zip on a real phone. The flash commands are the ones tools/smoke-test.sh already runs
on the Pixel 8; the end-to-end run of install.sh itself on a device is described in docs/runbooks/release.md.

Run: python3 -m unittest discover -s tools/tests -p 'test_install_sh.py' -v
"""
import hashlib
import os
import shutil
import stat
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
INSTALL_SH = HERE.parent / "install.sh"

FAKE_ADB = r"""#!/bin/sh
# fake adb for tools/tests/test_install_sh.py; state and answers live in $FAKE_DIR
D=$FAKE_DIR
echo "$*" >>"$D/log"
SER=
if [ "$1" = "-s" ]; then SER=$2; shift 2; fi
cmd=$1; shift
rd() { [ -f "$D/$1" ] && cat "$D/$1"; }
case $cmd in
devices)
    echo "List of devices attached"
    rd devices
    ;;
get-state)
    n=$(rd offline); n=${n:-0}
    if [ "$n" -gt 0 ]; then echo $((n - 1)) >"$D/offline"; echo offline; exit 1; fi
    echo device
    ;;
push) echo "1 file pushed" ;;
install)
    apk=$(basename "$2")
    if [ -f "$D/install_fail_$apk" ]; then cat "$D/install_fail_$apk"; exit 1; fi
    echo Success
    ;;
reboot) : >"$D/rebooted"; echo 2 >"$D/offline" ;;
shell)
    c="$*"
    case $c in
    "getprop ro.product.model") echo FakePhone ;;
    "getprop ro.build.version.release") echo 15 ;;
    "getprop sys.boot_completed") echo 1 ;;
    "mkdir -p "* | "rm -rf "*) ;;
    "pm list packages --show-versioncode org.agentos.app") echo "package:org.agentos.app versionCode:$(rd pm_vc)" ;;
    "pm list packages org.agentos.app") [ -f "$D/app_installed" ] && echo package:org.agentos.app ;;
    "su -c 'command -v magisk'") [ "$(rd manager)" = magisk ] && echo /system/bin/magisk ;;
    "su -c 'ls /data/adb/ksud'") [ "$(rd manager)" = ksu ] && echo /data/adb/ksud ;;
    "su -c 'magisk --install-module "*) rd flash_out ;;
    "su -c '/data/adb/ksud module install "*) rd flash_out ;;
    "su -c 'grep -h ^version="*) rd staged ;;
    "su -c 'grep "*"install: org.agentos.app result="*)
        if [ -f "$D/rebooted" ]; then rd line_after; else rd line_before; fi
        ;;
    *) echo "fake adb: unhandled shell command: $c" >>"$D/unhandled" ;;
    esac
    ;;
*) echo "fake adb: unhandled: $cmd $*" >>"$D/unhandled" ;;
esac
exit 0
"""

CHECK_DEVICE_STUB = """#!/bin/sh
echo "PASS  stub check ($ANDROID_SERIAL, zip $1)"
echo "$ANDROID_SERIAL $1" >>"$FAKE_DIR/check_called"
[ -f "$FAKE_DIR/check_fail" ] && { echo "FAIL  stub check failed"; exit 1; }
exit 0
"""

SAMPLES = ("alarm", "calendar", "notes", "todo", "sms")
LINE_OK = "10-09 12:00:00 up=40 install: org.agentos.app result=upgraded installed_vc=1"


def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


class InstallShTests(unittest.TestCase):
    LANG = "C"

    def setUp(self):
        self._t = tempfile.TemporaryDirectory()
        self.addCleanup(self._t.cleanup)
        self.root = Path(self._t.name)
        self.bundle = self.root / "bundle"
        self.fake = self.root / "fake"
        self.bin = self.root / "bin"
        for d in (self.bundle, self.fake, self.bin):
            d.mkdir()
        adb = self.bin / "adb"
        adb.write_text(FAKE_ADB)
        adb.chmod(adb.stat().st_mode | stat.S_IXUSR)
        self.make_bundle()
        self.fake_set("devices", "FAKE1\tdevice\n")
        self.fake_set("manager", "magisk")
        self.fake_set("staged", "version=0.1.0\n")
        self.fake_set("line_before", "")
        self.fake_set("line_after", LINE_OK + "\n")
        self.fake_set("pm_vc", "1")

    # -- fixtures
    def fake_set(self, name, text):
        (self.fake / name).write_text(text)

    def make_bundle(self, version="0.1.0", variant="release", zip_name=None, samples=SAMPLES, sums=True):
        zname = zip_name or f"agentos-{version}.zip"
        with zipfile.ZipFile(self.bundle / zname, "w") as z:
            z.writestr("module.prop", f"id=agentos\nversion={version}\nversionCode=1\n")
            z.writestr("common.sh", "# common\n")
            z.writestr("support-matrix.yaml", "matrix_version: 1\n")
            z.writestr("app/apks.list", "org.agentos.app 1 app/app.apk " + "0" * 64 + " " + "1" * 64 + "\n")
        for s in samples:
            (self.bundle / f"AgentOS-Sample-{s}-{version}.apk").write_bytes(os.urandom(64) + s.encode())
        (self.bundle / "check-device.sh").write_text(CHECK_DEVICE_STUB)
        (self.bundle / "build-info.json").write_text('{"variant": "%s"}\n' % variant)
        if sums:
            files = sorted(p for p in self.bundle.iterdir() if p.name not in ("SHA256SUMS", "build-info.json"))
            (self.bundle / "SHA256SUMS").write_text("".join(f"{sha(p)}  {p.name}\n" for p in files))

    def run_sh(self, *args, shell="sh", lang=None, extra_env=None, stdin=subprocess.DEVNULL):
        # Every test runs in the language of the class that runs it (English and Chinese): `$VAR` directly followed by a full-width character
        # is read by bash 3.2 and zsh as part of the variable name and dies under `set -u`; only the Chinese run can see that.
        lang = lang or self.LANG
        env = {
            "PATH": f"{self.bin}:/usr/bin:/bin:/usr/sbin:/sbin",
            "FAKE_DIR": str(self.fake), "AGENTOS_NAP": "0", "LANG": lang, "HOME": str(self.root), "TMPDIR": str(self.root),
        }
        env.update(extra_env or {})
        return subprocess.run([shell, str(INSTALL_SH), "-d", str(self.bundle), *args], capture_output=True, text=True, env=env, stdin=stdin, timeout=120)

    def says(self, text, *phrases):
        """True when the text contains one of the phrases (give the English and the Chinese wording of the same message)."""
        return any(p in text for p in phrases)

    def log(self):
        p = self.fake / "log"
        return p.read_text().splitlines() if p.exists() else []

    def calls(self, word):
        return [l for l in self.log() if word in l]

    def assert_untouched(self, r):
        for w in ("push", "install", "reboot", "magisk --install-module", "ksud module install"):
            self.assertEqual(self.calls(w), [], f"{w} ran although it should not have: {r.stdout}{r.stderr}")

    # -- the happy path
    def test_magisk_flash_reboot_and_default_samples(self):
        r = self.run_sh("-y")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        log = self.log()
        self.assertTrue(any("magisk --install-module" in l for l in log))
        self.assertFalse(any("ksud" in l for l in log))
        self.assertEqual(len(self.calls("reboot")), 1)
        self.assertLess(log.index(next(l for l in log if "magisk --install-module" in l)), log.index(next(l for l in log if l.endswith("reboot") or " reboot" in l)))
        installed = [l for l in log if " install -r " in l]
        self.assertEqual(len(installed), 4)
        self.assertFalse(any("Sample-sms" in l for l in installed), "the messages app is only installed when it is named")
        self.assertTrue(self.says(r.stdout, "AgentOS App: upgraded", "AgentOS App：upgraded"), r.stdout)
        self.assertNotIn("unhandled", "".join(os.listdir(self.fake)))

    def test_every_adb_call_after_devices_carries_the_serial(self):
        self.run_sh("-y")
        for l in self.log():
            if l == "devices":
                continue
            self.assertTrue(l.startswith("-s FAKE1 "), l)

    def test_kernelsu_uses_ksud(self):
        self.fake_set("manager", "ksu")
        r = self.run_sh("-y")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertTrue(any("ksud module install" in l for l in self.log()))
        self.assertFalse(any("magisk --install-module" in l for l in self.log()))

    def test_the_stub_pre_flight_gets_the_serial_and_the_zip(self):
        self.run_sh("-y")
        called = (self.fake / "check_called").read_text()
        self.assertIn("FAKE1 ", called)
        self.assertIn("agentos-0.1.0.zip", called)

    def test_staging_dir_is_removed_on_the_phone(self):
        self.run_sh("-y")
        self.assertTrue(any("rm -rf /data/local/tmp/agentos-install" in l for l in self.log()))

    # -- refusals before anything is changed
    def test_no_root_manager(self):
        self.fake_set("manager", "none")
        r = self.run_sh("-y")
        self.assertEqual(r.returncode, 1)
        self.assertEqual(self.calls("reboot"), [])
        self.assertTrue(self.says(r.stderr, "neither Magisk nor KernelSU", "没找到 Magisk 或 KernelSU"), r.stderr)

    def test_flash_refused_by_the_module_never_reboots(self):
        self.fake_set("flash_out", "- Installing\n! 不支持的 Android 版本（API 34）\n")
        r = self.run_sh("-y")
        self.assertEqual(r.returncode, 1)
        self.assertEqual(self.calls("reboot"), [])
        self.assertTrue(self.says(r.stderr, "refused the module", "拒绝了这个模块"), r.stderr)
        self.assertIn("API 34", r.stdout)

    def test_staged_version_must_be_the_zips(self):
        self.fake_set("staged", "version=0.0.9\n")
        r = self.run_sh("-y")
        self.assertEqual(r.returncode, 1)
        self.assertEqual(self.calls("reboot"), [])

    def test_failed_pre_flight_stops_before_the_phone_is_touched(self):
        self.fake_set("check_fail", "1")
        r = self.run_sh("-y")
        self.assertEqual(r.returncode, 1)
        self.assertTrue(self.says(r.stderr, "pre-flight failed", "前检没通过"), r.stderr)
        self.assert_untouched(r)

    def test_check_only(self):
        r = self.run_sh("--check-only")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assert_untouched(r)

    def test_wrong_checksum_stops_before_adb_is_used_for_anything_but_listing(self):
        apk = self.bundle / "AgentOS-Sample-notes-0.1.0.apk"
        apk.write_bytes(apk.read_bytes() + b"tampered")
        r = self.run_sh("-y")
        self.assertEqual(r.returncode, 1)
        self.assertTrue(self.says(r.stderr, "wrong SHA-256", "SHA-256 不对"), r.stderr)
        self.assertEqual([l for l in self.log() if l != "devices"], [])

    def test_a_file_missing_from_sha256sums(self):
        lines = (self.bundle / "SHA256SUMS").read_text().splitlines()
        (self.bundle / "SHA256SUMS").write_text("\n".join(l for l in lines if "Sample-todo" not in l) + "\n")
        r = self.run_sh("-y")
        self.assertEqual(r.returncode, 1)
        self.assertTrue(self.says(r.stderr, "does not list", "里没有"), r.stderr)
        self.assert_untouched(r)

    def test_no_sha256sums_file(self):
        (self.bundle / "SHA256SUMS").unlink()
        r = self.run_sh("-y")
        self.assertEqual(r.returncode, 1)
        self.assertIn("SHA256SUMS", r.stderr)  # the file name is in both languages

    def test_no_verify_lets_a_bundle_without_sums_through(self):
        (self.bundle / "SHA256SUMS").unlink()
        r = self.run_sh("-y", "--no-verify")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_check_device_script_itself_is_verified(self):
        (self.bundle / "check-device.sh").write_text(CHECK_DEVICE_STUB + "# tampered\n")
        r = self.run_sh("-y")
        self.assertEqual(r.returncode, 1)
        self.assertTrue(self.says(r.stderr, "check-device.sh has the wrong SHA-256", "check-device.sh 的 SHA-256 不对"), r.stderr)
        self.assert_untouched(r)

    def test_debug_bundle_needs_a_flag(self):
        self.make_bundle(variant="debug")
        r = self.run_sh("-y")
        self.assertEqual(r.returncode, 1)
        self.assertTrue(self.says(r.stderr, "debug test bundle", "debug 测试包"), r.stderr)
        self.assert_untouched(r)
        r = self.run_sh("-y", "--allow-debug")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_unsigned_dry_run_zip_is_refused(self):
        for f in self.bundle.glob("agentos-*.zip"):
            f.unlink()
        self.make_bundle(zip_name="agentos-0.1.0-unsigned.zip")
        r = self.run_sh("-y")
        self.assertEqual(r.returncode, 1)
        self.assertTrue(self.says(r.stderr, "unsigned", "未签名"), r.stderr)
        self.assert_untouched(r)

    def test_two_zips_are_ambiguous(self):
        shutil.copy(self.bundle / "agentos-0.1.0.zip", self.bundle / "agentos-0.2.0.zip")
        r = self.run_sh("-y")
        self.assertEqual(r.returncode, 1)
        self.assertTrue(self.says(r.stderr, "exactly one agentos-", "应当正好有一个 agentos-"), r.stderr)

    def test_a_truncated_zip(self):
        z = self.bundle / "agentos-0.1.0.zip"
        z.write_bytes(z.read_bytes()[:40])
        r = self.run_sh("-y", "--no-verify")
        self.assertEqual(r.returncode, 1)
        self.assertTrue(self.says(r.stderr, "not a complete zip", "不是完整的 zip"), r.stderr)

    # -- choosing the phone
    def test_no_phone_connected(self):
        self.fake_set("devices", "")
        r = self.run_sh("-y")
        self.assertEqual(r.returncode, 1)
        self.assertTrue(self.says(r.stderr, "no phone connected", "没有连接的手机"), r.stderr)

    def test_unauthorized_phone_is_explained(self):
        self.fake_set("devices", "FAKE1\tunauthorized\n")
        r = self.run_sh("-y")
        self.assertEqual(r.returncode, 1)
        self.assertIn("unauthorized", r.stderr)  # adb's own word, printed in both languages

    def test_two_phones_need_a_serial(self):
        self.fake_set("devices", "FAKE1\tdevice\nFAKE2\tdevice\n")
        r = self.run_sh("-y")
        self.assertEqual(r.returncode, 2)
        self.assertTrue(self.says(r.stderr, "pick one with -s", "请用 -s 指定"), r.stderr)
        self.assert_untouched(r)
        r = self.run_sh("-y", "-s", "FAKE2")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertTrue(all(l.startswith("-s FAKE2 ") for l in self.log() if l != "devices"))

    def test_a_serial_that_is_not_online(self):
        r = self.run_sh("-y", "-s", "NOPE")
        self.assertEqual(r.returncode, 1)
        self.assertTrue(self.says(r.stderr, "not online", "不在线"), r.stderr)

    def test_android_serial_env(self):
        self.fake_set("devices", "FAKE1\tdevice\nFAKE2\tdevice\n")
        r = self.run_sh("-y", extra_env={"ANDROID_SERIAL": "FAKE1"})
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    # -- confirmation
    def test_without_a_terminal_it_needs_yes(self):
        r = self.run_sh()
        self.assertEqual(r.returncode, 1)
        self.assertIn("--yes", r.stderr)  # the flag name is in both languages
        self.assert_untouched(r)

    # -- samples
    def test_samples_only_does_not_flash_or_reboot(self):
        r = self.run_sh("-y", "--samples-only", "--samples", "todo,sms")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual(self.calls("reboot") + self.calls("magisk") + self.calls("push"), [])
        self.assertEqual(len([l for l in self.log() if " install -r " in l]), 2)
        self.assertTrue(any("Sample-sms" in l for l in self.log()))

    def test_samples_only_works_without_a_module_zip_in_the_folder(self):
        for f in self.bundle.glob("agentos-*.zip"):
            f.unlink()
        r = self.run_sh("-y", "--samples-only")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_samples_none_and_all(self):
        r = self.run_sh("-y", "--samples", "none")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual([l for l in self.log() if " install -r " in l], [])
        self.setUp_log_reset()
        r = self.run_sh("-y", "--samples", "all", "--no-reboot")
        self.assertEqual(len([l for l in self.log() if " install -r " in l]), 5)

    def setUp_log_reset(self):
        (self.fake / "log").write_text("")
        (self.fake / "rebooted").unlink(missing_ok=True)
        (self.fake / "offline").unlink(missing_ok=True)

    def test_unknown_sample(self):
        r = self.run_sh("-y", "--samples", "todo,camera")
        self.assertEqual(r.returncode, 2)
        self.assertTrue(self.says(r.stderr, "unknown sample app: camera", "不认识的示例 App：camera"), r.stderr)

    def test_samples_only_with_none_selected(self):
        r = self.run_sh("-y", "--samples-only", "--samples", "none")
        self.assertEqual(r.returncode, 2)

    def test_a_sample_with_another_signature_is_reported_and_the_rest_still_installed(self):
        self.fake_set("install_fail_AgentOS-Sample-alarm-0.1.0.apk", "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]\n")
        r = self.run_sh("-y", "--samples-only")
        self.assertEqual(r.returncode, 1)
        self.assertTrue(self.says(r.stdout, "another signature", "签名不同"), r.stdout)
        self.assertEqual(len([l for l in self.log() if " install -r " in l]), 4)

    # -- reboot and the module's own verdict
    def test_no_reboot_flag(self):
        r = self.run_sh("-y", "--no-reboot")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual(self.calls("reboot"), [])
        self.assertTrue(self.says(r.stdout, "No reboot", "没有重启"), r.stdout)

    def test_signature_mismatch_is_reported_as_exit_3(self):
        self.fake_set("line_after", "10-09 12:00:00 up=40 install: org.agentos.app result=sig_mismatch installed_vc=1\n")
        r = self.run_sh("-y")
        self.assertEqual(r.returncode, 3, r.stdout + r.stderr)
        self.assertTrue(self.says(r.stdout, "another certificate", "签名不一致"), r.stdout)
        self.assertTrue(self.says(r.stdout, "never uninstalls", "不会卸载"), r.stdout)

    def test_other_bad_results_are_exit_3(self):
        for res in ("failed", "corrupt", "bad_list"):
            with self.subTest(res=res):
                self.setUp_log_reset()
                self.fake_set("line_after", f"10-09 12:00:00 up=40 install: org.agentos.app result={res} installed_vc=none\n")
                r = self.run_sh("-y", "--samples", "none")
                self.assertEqual(r.returncode, 3, r.stdout + r.stderr)
                self.assertIn(res, r.stdout)

    def test_good_results(self):
        for res in ("installed", "upgraded", "same", "newer"):
            with self.subTest(res=res):
                self.setUp_log_reset()
                self.fake_set("line_after", f"10-09 12:00:00 up=40 install: org.agentos.app result={res} installed_vc=1\n")
                r = self.run_sh("-y", "--samples", "none")
                self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_a_result_line_from_the_previous_boot_does_not_count(self):
        # same text before and after the reboot: the module did not run (yet) in this boot; versionCode alone would have said "fine"
        self.fake_set("line_before", LINE_OK + "\n")
        self.fake_set("line_after", LINE_OK + "\n")
        r = self.run_sh("-y", "--samples", "none")
        self.assertEqual(r.returncode, 3, r.stdout + r.stderr)
        self.assertTrue(self.says(r.stdout, "wrote no install result", "没有记下安装结果"), r.stdout)

    def test_a_new_line_after_an_older_one_counts(self):
        self.fake_set("line_before", "10-08 09:00:00 up=40 install: org.agentos.app result=installed installed_vc=1\n")
        r = self.run_sh("-y", "--samples", "none")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    # -- AGENTOS_ADB_ROOT (an adb-root emulator has no `su -c`)
    def test_root_commands_use_su_c_on_a_real_phone_and_plain_shell_with_the_emulator_switch(self):
        self.run_sh("-y", "--samples", "none")
        real = [l for l in self.log() if "magisk --install-module" in l]
        self.assertTrue(real and all("su -c" in l for l in real), real)
        self.setUp_log_reset()
        self.fake_set("manager", "magisk")
        self.run_sh("-y", "--samples", "none", extra_env={"AGENTOS_ADB_ROOT": "1"})
        emu = [l for l in self.log() if "magisk --install-module" in l or "command -v magisk" in l]
        self.assertTrue(emu, self.log())
        self.assertTrue(all("su -c" not in l for l in emu), emu)

    # -- usage and language
    def test_unknown_option(self):
        r = self.run_sh("--frobnicate")
        self.assertEqual(r.returncode, 2)

    def test_help(self):
        r = self.run_sh("--help")
        self.assertEqual(r.returncode, 0)
        self.assertIn("--samples-only", r.stdout)

    def test_messages_follow_the_language(self):
        zh = self.run_sh("--check-only", lang="zh_CN.UTF-8")
        en = self.run_sh("--check-only", lang="en_US.UTF-8")
        self.assertIn("选择手机", zh.stdout)
        self.assertIn("Choosing the phone", en.stdout)
        forced = self.run_sh("--check-only", lang="en_US.UTF-8", extra_env={"AGENTOS_LANG": "zh"})
        self.assertIn("选择手机", forced.stdout)

    def test_runs_under_every_posix_shell_that_is_installed(self):
        tried = 0
        for shell in ("sh", "bash", "dash", "zsh"):
            if shutil.which(shell) is None:
                continue
            tried += 1
            with self.subTest(shell=shell):
                self.setUp_log_reset()
                r = self.run_sh("-y", "--samples", "todo", shell=shell)
                self.assertEqual(r.returncode, 0, f"{shell}: {r.stdout}{r.stderr}")
        self.assertGreater(tried, 0)

    def test_the_script_has_no_bashisms_that_break_on_the_sh_that_macos_ships(self):
        text = INSTALL_SH.read_text()
        self.assertTrue(text.startswith("#!/bin/sh"))
        for bad in ("[[", "declare ", "local ", "mapfile", "readarray", "${!", "<<<", "echo -e", "source "):
            self.assertNotIn(bad, text, bad)


class InstallShChineseTests(InstallShTests):
    """The same tests, with a Chinese locale. Tests that look for an English sentence are skipped here (the Chinese wording is checked in the one
    test that compares both languages)."""

    LANG = "zh_CN.UTF-8"

    def run_sh(self, *args, **kw):
        r = super().run_sh(*args, **kw)
        # the script must never print an unbound-variable error, whatever the language
        self.assertNotIn("unbound variable", r.stderr)
        self.assertNotIn("bad substitution", r.stderr)
        return r


if __name__ == "__main__":
    unittest.main()
