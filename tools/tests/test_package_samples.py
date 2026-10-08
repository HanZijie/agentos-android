"""tools/package-samples.py 的纯函数部分的单元测试（不需要 Android SDK 和 APK）。
运行：python3 -m unittest discover -s tools/tests -p 'test_*.py' -v
"""
import importlib.util
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("package_samples", HERE.parent / "package-samples.py")
ps = importlib.util.module_from_spec(spec)
sys.modules["package_samples"] = ps
spec.loader.exec_module(ps)

SAMPLE = {"name": "alarm", "application_id": "org.agentos.sample.alarm", "dir": Path("/x")}

BADGING = """package: name='org.agentos.sample.alarm' versionCode='1' versionName='0.1.0' platformBuildVersionName='16'
sdkVersion:'35'
targetSdkVersion:'36'
uses-permission: name='android.permission.VIBRATE'
uses-permission: name='android.permission.WAKE_LOCK'
uses-permission: name='org.agentos.sample.alarm.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
locales: '--_--' 'af' 'en' 'en-XA' 'zh-CN'
"""


class Badging(unittest.TestCase):
    def test_parse(self):
        b = ps.parse_badging(BADGING)
        self.assertEqual((b["package"], b["versionCode"], b["versionName"]), ("org.agentos.sample.alarm", "1", "0.1.0"))
        self.assertIn("en", b["locales"])
        self.assertEqual(b["minSdk"], "35")
        self.assertEqual(len(b["permissions"]), 3)

    def test_clean(self):
        b = ps.parse_badging(BADGING)
        self.assertEqual(ps.check_badging(SAMPLE, b, "0.1.0", "1", {"android.permission.VIBRATE", "android.permission.WAKE_LOCK"}), [])

    def test_problems(self):
        b = ps.parse_badging(BADGING.replace("'en' ", ""))
        problems = ps.check_badging(SAMPLE, b, "0.2.0", "2", {"android.permission.VIBRATE", "android.permission.CAMERA"})
        text = "\n".join(problems)
        self.assertIn("versionName 0.1.0 != agentos.version 0.2.0", text)
        self.assertIn("versionCode 1 != agentos.versionCode 2", text)
        self.assertIn("no `en` resources", text)
        self.assertIn("requests android.permission.WAKE_LOCK which is not in", text)  # 多了
        self.assertIn("lists android.permission.CAMERA but the APK does not request it", text)  # 少了

    def test_wrong_package_and_missing_entry(self):
        b = ps.parse_badging(BADGING.replace("sample.alarm' versionCode", "sample.other' versionCode"))
        problems = ps.check_badging(SAMPLE, b, "0.1.0", "1", None)
        self.assertTrue(any("package is org.agentos.sample.other" in p for p in problems))
        self.assertTrue(any("no entry in" in p for p in problems))


TREE = """N: android=http://schemas.android.com/apk/res/android
  E: manifest (line=2)
      E: application (line=20)
          E: activity (line=39)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="org.agentos.sample.alarm.ui.MainActivity" (Raw: "x")
          E: receiver (line=84)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="org.agentos.sample.alarm.debug.DebugToolReceiver" (Raw: "x")
            A: http://schemas.android.com/apk/res/android:permission(0x01010006)="android.permission.DUMP" (Raw: "x")
          E: receiver (line=90)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="androidx.profileinstall.ProfileInstallReceiver" (Raw: "x")
            A: http://schemas.android.com/apk/res/android:permission(0x01010006)="android.permission.DUMP" (Raw: "x")
"""


class Components(unittest.TestCase):
    def test_components_and_debug(self):
        comps = ps.manifest_components(TREE)
        self.assertEqual(comps, ["org.agentos.sample.alarm.ui.MainActivity", "org.agentos.sample.alarm.debug.DebugToolReceiver", "androidx.profileinstall.ProfileInstallReceiver"])
        bad = ps.check_no_debug_components(SAMPLE, comps)
        self.assertEqual(len(bad), 1)  # a library's DUMP receiver (profileinstaller) is not ours
        self.assertIn("DebugToolReceiver", bad[0])

    def test_dex_scan(self):
        self.assertTrue(ps.dex_has_debug_classes([b"..Lorg/agentos/sample/alarm/debug/SelfTestReceiver;.."], "org.agentos.sample.alarm"))
        self.assertFalse(ps.dex_has_debug_classes([b"..Lorg/agentos/sample/alarm/ring/AlarmReceiver;.."], "org.agentos.sample.alarm"))
        self.assertFalse(ps.dex_has_debug_classes([b"..Lorg/agentos/sample/notes/debug/X;.."], "org.agentos.sample.alarm"))


class Plugin(unittest.TestCase):
    GOOD = {"name": "alarm", "extensions": {"com.openai": {"interface": {"displayName": "闹钟"}}, "org.agentos": {"mcpServers": {"alarm": {"service": "x"}}}}}
    SKILL = {"assets/agent-plugin/skills/alarm/SKILL.md", "assets/agent-plugin/plugin.json"}

    def test_good(self):
        self.assertEqual(ps.check_plugin_files(SAMPLE, self.SKILL, self.GOOD), [])

    def test_bad(self):
        self.assertTrue(ps.check_plugin_files(SAMPLE, set(), None))
        bad = dict(self.GOOD, name="clock", extensions={})
        text = "\n".join(ps.check_plugin_files(SAMPLE, {"assets/agent-plugin/plugin.json"}, bad))
        self.assertIn("name is 'clock'", text)
        self.assertIn("displayName", text)
        self.assertIn("mcpServers", text)
        self.assertIn("SKILL.md", text)


class Misc(unittest.TestCase):
    def test_release_notes(self):
        self.assertEqual(ps.check_release_notes("# 0.1.0\n## 已知限制\n- 无\n\n# English\n## Known limitations\n- none\n"), [])
        self.assertEqual(len(ps.check_release_notes("# 0.1.0\n## Known limitations\n")), 1)
        self.assertEqual(len(ps.check_release_notes("")), 2)

    def test_sums_format(self):
        self.assertEqual(ps.sums_text([("a.apk", "ab" * 32), ("b.apk", "cd" * 32)]), "ab" * 32 + "  a.apk\n" + "cd" * 32 + "  b.apk\n")

    def test_discover_and_permissions(self):
        with tempfile.TemporaryDirectory() as t:
            root = Path(t)
            (root / "plugins/samples/todo").mkdir(parents=True)
            (root / "plugins/samples/todo/build.gradle.kts").write_text('defaultConfig {\n applicationId = "org.agentos.sample.todo"\n}\n')
            (root / "plugins/samples/docs-only").mkdir()
            found = ps.discover_samples(root)
            self.assertEqual([(s["name"], s["application_id"]) for s in found], [("todo", "org.agentos.sample.todo")])
            f = root / "perm.json"
            f.write_text(json.dumps({"_comment": "x", "todo": ["a", "b"]}))
            self.assertEqual(ps.load_permissions(f), {"todo": {"a", "b"}})

    def test_real_permissions_file_parses(self):
        perms = ps.load_permissions(ps.PERMISSIONS_FILE)
        self.assertIn("alarm", perms)
        samples = {s["name"] for s in ps.discover_samples(ps.ROOT)}
        self.assertEqual(samples - set(perms), set(), "every sample needs an entry in tools/package-samples.permissions.json")


if __name__ == "__main__":
    unittest.main()
