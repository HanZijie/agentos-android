"""tools/check-i18n.py 的单元测试：用临时目录造小仓库，不依赖真实仓库内容。
运行：python3 -m unittest discover -s tools/tests -p 'test_*.py' -v
"""
import importlib.util
import io
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("check_i18n", os.path.join(HERE, "..", "check-i18n.py"))
check = importlib.util.module_from_spec(spec)
sys.modules["check_i18n"] = check
spec.loader.exec_module(check)


def write(root, rel, text):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def res(*items):
    return '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + "\n".join(items) + "\n</resources>\n"


class Repo(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = self._tmp.name
        self.addCleanup(self._tmp.cleanup)

    def run_check(self, allowlist=None):
        if allowlist is not None:
            write(self.root, "tools/check-i18n.allowlist", allowlist)
        out = io.StringIO()
        code = check.run(self.root, os.path.join(self.root, "tools", "check-i18n.allowlist"), out=out)
        return code, out.getvalue()

    def pair(self, zh, en, name="app"):
        write(self.root, name + "/src/main/res/values/strings.xml", res(*zh))
        write(self.root, name + "/src/main/res/values-en/strings.xml", res(*en))


class ResourceTests(Repo):
    def test_aligned_resources_pass(self):
        self.pair(['<string name="a">你好 %1$s，共 %2$d 条</string>', '<plurals name="n"><item quantity="other">%d 个</item></plurals>',
                   '<string-array name="arr"><item>一</item><item>二</item></string-array>'],
                  ['<string name="a">Hello %1$s, %2$d total</string>',
                   '<plurals name="n"><item quantity="one">One</item><item quantity="other">%d items</item></plurals>',
                   '<string-array name="arr"><item>One</item><item>Two</item></string-array>'])
        code, out = self.run_check()
        self.assertEqual(code, 0, out)

    def test_missing_and_extra_keys(self):
        self.pair(['<string name="a">甲</string>', '<string name="b">乙</string>'], ['<string name="a">A</string>', '<string name="c">C</string>'])
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("[missing-en] b:", out)
        self.assertIn("[extra-en] c:", out)

    def test_not_translatable_and_ignore_are_allowed(self):
        self.pair(['<string name="id" translatable="false">x</string>',
                   '<string name="only_zh" xmlns:tools="http://schemas.android.com/tools" tools:ignore="MissingTranslation">仅中文</string>'], [])
        code, out = self.run_check()
        self.assertEqual(code, 0, out)

    def test_placeholder_mismatch(self):
        self.pair(['<string name="a">共 %d 条</string>', '<string name="b">%1$s 和 %2$s</string>', '<string name="c">100%%</string>'],
                  ['<string name="a">total %s</string>', '<string name="b">%2$s and %1$s</string>', '<string name="c">100%%</string>'])
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("[placeholder] a:", out)
        self.assertNotIn("] b:", out)  # 位置参数换了顺序是允许的
        self.assertNotIn("] c:", out)  # %% 不是占位符

    def test_positional_conversion_must_match(self):
        self.pair(['<string name="b">%1$s 和 %2$d</string>'], ['<string name="b">%1$d and %2$s</string>'])
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("[placeholder] b:", out)

    def test_plurals_rules(self):
        self.pair(['<plurals name="a"><item quantity="one">一个</item><item quantity="other">%d 个</item></plurals>',
                   '<plurals name="b"><item quantity="other">%d 个</item></plurals>',
                   '<plurals name="c"><item quantity="other">%d 个</item></plurals>'],
                  ['<plurals name="a"><item quantity="one">One</item><item quantity="other">%d items</item></plurals>',
                   '<plurals name="b"><item quantity="other">items</item></plurals>',
                   '<plurals name="c"><item quantity="few">%d x</item><item quantity="other">%d items</item></plurals>'])
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("[plurals-zh] a:", out)  # 中文只能写 other
        self.assertIn("[placeholder] b (other):", out)
        self.assertIn("[plurals-en] c:", out)  # 英文只能 one / other

    def test_array_size(self):
        self.pair(['<string-array name="x"><item>一</item><item>二</item></string-array>'], ['<string-array name="x"><item>One</item></string-array>'])
        code, out = self.run_check()
        self.assertIn("[array-size] x:", out)

    def test_cjk_in_english_resource(self):
        self.pair(['<string name="a">甲</string>'], ['<string name="a">A，B</string>'])
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("[en-has-cjk]", out)

    def test_module_without_english_is_skipped(self):
        write(self.root, "app/src/main/res/values/strings.xml", res('<string name="a">甲</string>'))
        code, out = self.run_check()
        self.assertEqual(code, 0, out)


class LiteralTests(Repo):
    def kt(self, text, rel="app/src/main/java/A.kt"):
        write(self.root, rel, text)

    def test_cjk_literal_is_an_error(self):
        self.kt('val a = "你好"\n')
        code, out = self.run_check("")
        self.assertEqual(code, 1)
        self.assertIn("app/src/main/java/A.kt:1: [cjk-literal]", out)

    def test_comments_and_other_source_sets_do_not_count(self):
        self.kt('// "你好"\n/* 说明 "你好" /* 嵌套 */ 还在注释 */\n/** KDoc: “引号” */\nval a = "hello"\n')
        write(self.root, "app/src/test/java/T.kt", 'val t = "你好"\n')
        write(self.root, "app/src/debug/java/D.kt", 'val d = "你好"\n')
        code, out = self.run_check("")
        self.assertEqual(code, 0, out)

    def test_templates_raw_strings_and_escapes(self):
        self.kt('val a = "x ${foo("嵌套")} y"\n')
        code, out = self.run_check("")
        self.assertEqual(code, 1)
        self.assertEqual(out.count("[cjk-literal]"), 1)
        self.kt('val b = """\n多行\n"""\n')
        code, out = self.run_check("")
        self.assertIn("A.kt:2: [cjk-literal]", out)
        self.kt('val c = "\\u4e2d"\nval d = "\\"ok\\" and { braces } ${1 + 1}"\n')
        code, out = self.run_check("")
        self.assertIn("A.kt:1: [cjk-literal]", out)  # \u4e2d 是“中”
        self.assertEqual(out.count("[cjk-literal]"), 1)

    def test_char_literal_and_code_with_braces(self):
        self.kt('fun f() { val c = \'，\'; if (c == \'x\') { } }\nval s = "plain"\n')
        code, out = self.run_check("")
        self.assertEqual(code, 1)
        self.assertEqual(out.count("[cjk-literal]"), 1)

    def test_waiver(self):
        self.kt('val a = "，"  // i18n-ok: tokenizer punctuation\n// i18n-ok: same\nval b = "。"\nval c = "好"\n')
        code, out = self.run_check("")
        self.assertEqual(code, 1)
        self.assertEqual(out.count("[cjk-literal]"), 1)
        self.assertIn("A.kt:4:", out)

    def test_allowlist_and_stale_entry(self):
        self.kt('val a = "你好"\n')
        code, out = self.run_check("app/src/main/java/*.kt\n")
        self.assertEqual(code, 0, out)
        self.kt('val a = "hello"\n')
        code, out = self.run_check("app/src/main/java/*.kt\n")
        self.assertEqual(code, 1)
        self.assertIn("[stale-allowlist]", out)

    def test_nested_worktrees_are_not_scanned(self):
        # AGENTS.md section 7: other checkouts of the repository live in .worktrees/ and may be on a branch with a different allowlist
        self.kt('val a = "hello"\n')
        write(self.root, ".worktrees/other/app/src/main/java/B.kt", 'val b = "你好"\n')
        write(self.root, ".worktrees/other/app/src/main/res/values/strings.xml", res('<string name="x">甲</string>'))
        write(self.root, ".worktrees/other/app/src/main/res/values-en/strings.xml", res('<string name="x">A，B</string>'))
        code, out = self.run_check("")
        self.assertEqual(code, 0, out)

    def test_real_repo_passes(self):
        repo = os.path.abspath(os.path.join(HERE, "..", ".."))
        if not os.path.isdir(os.path.join(repo, "app")):
            self.skipTest("not inside the repository")
        out = io.StringIO()
        self.assertEqual(check.run(repo, os.path.join(repo, "tools", "check-i18n.allowlist"), out=out), 0, out.getvalue())


if __name__ == "__main__":
    unittest.main()
