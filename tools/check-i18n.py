#!/usr/bin/env python3
"""
中英文资源门禁（docs/next-apps-plan.md 第 7.2 节 R9）。不需要 Android SDK、不需要证书，秒级。

检查三件事，任何一件不过就退出码 1：

1. 资源对齐：每个同时有 `values/`（中文，默认）和 `values-en/`（英文）的 `res` 目录，
   - `<string>`、`<plurals>`、`<string-array>` 的 key 两边一一对应（`translatable="false"` 不算；
     `tools:ignore="MissingTranslation"` 的 key 允许只在中文里）；
   - 占位符对得上（`%s`、`%1$s`、`%d`、`%.2f`…按转换符比较；`%%` 不算）；
   - plurals：中文只能写 `other`，英文必须有 `other`、只能再加 `one`；占位符只比较 `other`
     （英文的 `one` 可以写成 “One alarm” 不带数字）；
   - string-array 两边条数相同。
2. 英文资源（`values-en/`）里不能出现中文（汉字、假名、全角标点、中文引号）。
3. `src/main` 里 Kotlin / Java 的字符串字面量不能含中文（注释不算，`src/test`、`src/debug` 等不看）。
   字面量不是界面文案时（如分词用的中文标点），在同一行或上一行写注释 `i18n-ok: 原因` 放行。
   现存的中文放在 `tools/check-i18n.allowlist`，一行一个仓库相对路径（可用 `*` 通配）；
   白名单里的文件如果已经没有中文了，也报错（要求移出，白名单只能缩小）。

用法：
    python3 tools/check-i18n.py                 # 检查整个仓库
    python3 tools/check-i18n.py --root DIR      # 指向别的仓库根（测试用）
    python3 tools/check-i18n.py --list-hits     # 列出 src/main 里含中文字面量的文件和个数（生成白名单用）

输出形式：`路径:行号: [规则] 说明`。
"""
import argparse
import fnmatch
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))

# 汉字、部首、假名、CJK 标点、全角形式（中文引号「」『』也在 3000–303F）
CJK = re.compile("[\u2e80-\u2fdf\u3000-\u303f\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uff00-\uffef]")

SKIP_DIRS = {".git", ".gradle", ".kotlin", ".idea", "build", "node_modules", ".mavis", "xhs-post", "reference", "spikes", "tests"}
SOURCE_EXTS = (".kt", ".java")

WAIVER = "i18n-ok"

PLACEHOLDER = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([tT][a-zA-Z]|[sSdDxXfFeEgGcCbBhHoO])")
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
TOOLS_NS = "{http://schemas.android.com/tools}"


class Report:
    def __init__(self):
        self.errors = []

    def add(self, path, line, rule, message):
        self.errors.append((path, line, rule, message))

    def print(self, out=sys.stdout):
        for path, line, rule, message in self.errors:
            out.write("%s:%s: [%s] %s\n" % (path, line if line else 1, rule, message))


# ---------------------------------------------------------------- 资源 XML

def _text(elem):
    return "".join(elem.itertext())


def placeholders(text):
    """(位置 -> 转换符 的字典或 None, 转换符的计数)。`%%` 先去掉。"""
    stripped = text.replace("%%", "")
    positional, plain = {}, []
    for m in PLACEHOLDER.finditer(stripped):
        conv = m.group(2).lower() if m.group(2)[0] not in "tT" else m.group(2)
        if m.group(1):
            positional[int(m.group(1))] = conv
        else:
            plain.append(conv)
    return positional, plain


def placeholders_match(zh, en):
    zp, zl = placeholders(zh)
    ep, el = placeholders(en)
    if zp and ep:
        return zp == ep and Counter(zl) == Counter(el)
    return Counter(list(zp.values()) + zl) == Counter(list(ep.values()) + el)


def describe(text):
    positional, plain = placeholders(text)
    parts = ["%d$%s" % (i, c) for i, c in sorted(positional.items())] + ["%" + c for c in plain]
    return "[" + ", ".join(parts) + "]"


def parse_values(path, report):
    """一个 values*/xxx.xml → {key: entry}。entry: ('string', text, line_hint, ignore_missing) 等。"""
    entries = {}
    try:
        tree = ET.parse(path)
    except ET.ParseError as e:
        report.add(path, 1, "xml", "cannot parse: %s" % e)
        return entries
    root = tree.getroot()
    for el in root:
        tag = el.tag
        name = el.get("name")
        if not name or el.get("translatable") == "false":
            continue
        ignore = "MissingTranslation" in (el.get(TOOLS_NS + "ignore") or "")
        if tag == "string":
            entries[name] = ("string", _text(el), ignore)
        elif tag == "plurals":
            items = {}
            for item in el.findall("item"):
                items[item.get("quantity")] = _text(item)
            entries[name] = ("plurals", items, ignore)
        elif tag == "string-array":
            entries[name] = ("array", [_text(i) for i in el.findall("item")], ignore)
    return entries


def line_of(path, key):
    """key 在文件里第一次出现的行号（只用于报错定位，找不到返回 1）。"""
    needle = 'name="%s"' % key
    try:
        with open(path, encoding="utf-8") as f:
            for n, line in enumerate(f, 1):
                if needle in line:
                    return n
    except OSError:
        pass
    return 1


def values_files(res_dir, folder):
    d = os.path.join(res_dir, folder)
    if not os.path.isdir(d):
        return []
    return sorted(os.path.join(d, f) for f in os.listdir(d) if f.endswith(".xml"))


def load_dir(res_dir, folder, report):
    """返回 ({key: entry}, {key: 文件})。同一个 key 出现在多个文件里取第一个。"""
    entries, where = {}, {}
    for path in values_files(res_dir, folder):
        for key, entry in parse_values(path, report).items():
            if key not in entries:
                entries[key] = entry
                where[key] = path
    return entries, where


def check_resources(res_dir, rel, report):
    zh_dir_exists = os.path.isdir(os.path.join(res_dir, "values"))
    en_dir_exists = os.path.isdir(os.path.join(res_dir, "values-en"))
    if not en_dir_exists:
        return False
    # 英文资源里不能出现中文
    for path in values_files(res_dir, "values-en"):
        with open(path, encoding="utf-8") as f:
            for n, line in enumerate(f, 1):
                m = CJK.search(line)
                if m:
                    report.add(rel(path), n, "en-has-cjk", "English resource contains CJK text: %r" % line.strip()[:80])
    if not zh_dir_exists:
        report.add(rel(os.path.join(res_dir, "values-en")), 1, "no-default", "values-en exists but values/ (the Chinese default) does not")
        return True
    zh, zh_where = load_dir(res_dir, "values", report)
    en, en_where = load_dir(res_dir, "values-en", report)
    for key, entry in sorted(zh.items()):
        if key not in en:
            if not entry[2]:
                report.add(rel(zh_where[key]), line_of(zh_where[key], key), "missing-en", "%s: no English translation (values-en)" % key)
    for key in sorted(en):
        if key not in zh:
            report.add(rel(en_where[key]), line_of(en_where[key], key), "extra-en", "%s: only in values-en (no Chinese default)" % key)
    for key in sorted(set(zh) & set(en)):
        z, e = zh[key], en[key]
        zpath, epath = rel(zh_where[key]), rel(en_where[key])
        eline = line_of(en_where[key], key)
        if z[0] != e[0]:
            report.add(epath, eline, "kind-mismatch", "%s: %s in values but %s in values-en" % (key, z[0], e[0]))
            continue
        if z[0] == "string":
            if not placeholders_match(z[1], e[1]):
                report.add(epath, eline, "placeholder", "%s: placeholders differ, zh %s vs en %s" % (key, describe(z[1]), describe(e[1])))
        elif z[0] == "array":
            if len(z[1]) != len(e[1]):
                report.add(epath, eline, "array-size", "%s: %d items in values vs %d in values-en" % (key, len(z[1]), len(e[1])))
        else:  # plurals
            zq, eq = z[1], e[1]
            if set(zq) != {"other"}:
                report.add(zpath, line_of(zh_where[key], key), "plurals-zh", "%s: Chinese plurals must only have 'other', has %s" % (key, sorted(zq)))
            if "other" not in eq or not set(eq) <= {"one", "other"}:
                report.add(epath, eline, "plurals-en", "%s: English plurals must have 'other' and may only add 'one', has %s" % (key, sorted(eq)))
            elif "other" in zq and not placeholders_match(zq["other"], eq["other"]):
                report.add(epath, eline, "placeholder", "%s (other): placeholders differ, zh %s vs en %s" % (key, describe(zq["other"]), describe(eq["other"])))
    return True


# ---------------------------------------------------------------- Kotlin / Java 字符串字面量

def string_literals(src):
    """产生 (行号, 字面量文本) —— 只含字面量本身的文本（不含 ${} 模板里的代码；模板里的嵌套字符串另算）。
    跳过 // 和 /* */ 注释（Kotlin 的块注释可嵌套）、字符字面量。"""
    n = len(src)
    i = 0
    line = 1
    # 栈：'code' 帧记录模板花括号深度；字符串帧 'str' / 'raw'
    stack = [("code", 0)]
    buf = []
    buf_line = 1
    out = []

    def flush():
        if buf:
            out.append((buf_line, "".join(buf)))
            buf.clear()

    while i < n:
        ch = src[i]
        top = stack[-1]
        if top[0] == "code":
            if ch == "\n":
                line += 1
                i += 1
            elif src.startswith("//", i):
                j = src.find("\n", i)
                i = n if j < 0 else j
            elif src.startswith("/*", i):
                depth, i = 1, i + 2
                while i < n and depth:
                    if src.startswith("/*", i):
                        depth += 1
                        i += 2
                    elif src.startswith("*/", i):
                        depth -= 1
                        i += 2
                    else:
                        if src[i] == "\n":
                            line += 1
                        i += 1
            elif src.startswith('"""', i):
                stack.append(("raw", 0))
                buf_line = line
                i += 3
            elif ch == '"':
                stack.append(("str", 0))
                buf_line = line
                i += 1
            elif ch == "'":
                # 字符字面量：'a'、'\n'、'\u4e2d'
                j = i + 1
                if j < n and src[j] == "\\":
                    j += 2
                    while j < n and src[j] != "'" and src[j] != "\n":
                        j += 1
                else:
                    j += 1
                if j < n and src[j] == "'":
                    lit = src[i + 1:j]
                    if CJK.search(lit):
                        out.append((line, lit))
                    i = j + 1
                else:
                    i += 1
            elif ch == "{" and top[1] >= 0 and len(stack) > 1:
                stack[-1] = ("code", top[1] + 1)
                i += 1
            elif ch == "}" and len(stack) > 1:
                if top[1] == 0:
                    stack.pop()  # 模板结束，回到字符串
                    buf_line = line
                else:
                    stack[-1] = ("code", top[1] - 1)
                i += 1
            else:
                i += 1
        else:
            kind = top[0]
            if kind == "str":
                if ch == "\\" and i + 1 < n:
                    if src[i + 1] == "u" and re.match(r"[0-9a-fA-F]{4}", src[i + 2:i + 6]):
                        buf.append(chr(int(src[i + 2:i + 6], 16)))
                        i += 6
                    else:
                        i += 2
                elif ch == '"':
                    flush()
                    stack.pop()
                    i += 1
                elif ch == "\n":  # 不应出现；容错，避免一个引号错位吞掉整个文件
                    flush()
                    stack.pop()
                else:
                    if ch == "$" and i + 1 < n and src[i + 1] == "{":
                        flush()
                        stack.append(("code", 0))
                        i += 2
                        continue
                    buf.append(ch)
                    i += 1
            else:  # raw
                if src.startswith('"""', i):
                    # 结尾可能是 """" 这种多于三个引号：多出来的引号属于内容
                    j = i
                    while j < n and src[j] == '"':
                        j += 1
                    k = j - 3
                    buf.append(src[i:k])
                    flush()
                    stack.pop()
                    i = j
                elif ch == "$" and i + 1 < n and src[i + 1] == "{":
                    flush()
                    stack.append(("code", 0))
                    i += 2
                else:
                    if ch == "\n":
                        line += 1
                    buf.append(ch)
                    i += 1
    flush()
    return out


def cjk_literal_lines(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        src = f.read()
    hits = []
    lines = src.split("\n")

    def waived(n):
        # 字面量本身不是界面文案（比如分词用的中文标点）时，在同一行或上一行写 `i18n-ok: 原因`
        return any(0 <= k < len(lines) and WAIVER in lines[k] for k in (n - 1, n - 2))

    for line, text in string_literals(src):
        if CJK.search(text) and not waived(line):
            # 多行 raw 字符串：把行号对准第一个含中文的行
            offset = 0
            for k, part in enumerate(text.split("\n")):
                if CJK.search(part):
                    offset = k
                    break
            hits.append((line + offset, text.strip().replace("\n", " ")[:60]))
    return hits


# ---------------------------------------------------------------- 遍历与主流程

def walk(root):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        yield dirpath, filenames


def load_allowlist(path):
    patterns = []
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as f:
            for raw in f:
                s = raw.split("#", 1)[0].strip()
                if s:
                    patterns.append(s)
    return patterns


def is_allowed(rel_path, patterns):
    return any(fnmatch.fnmatch(rel_path, p) for p in patterns)


def run(root, allowlist_path, list_hits=False, out=sys.stdout):
    report = Report()
    root = os.path.abspath(root)

    def rel(p):
        return os.path.relpath(p, root).replace(os.sep, "/")

    checked_res, kotlin_hits = [], {}
    for dirpath, filenames in walk(root):
        parts = rel(dirpath).split("/")
        # 资源：src/<sourceSet>/res
        if parts[-1] == "res" and len(parts) >= 3 and parts[-3] == "src":
            if check_resources(dirpath, rel, report):
                checked_res.append(rel(dirpath))
        # 代码：只看 src/main
        if "src" in parts:
            idx = len(parts) - 1 - parts[::-1].index("src")
            if idx + 1 < len(parts) and parts[idx + 1] == "main":
                for f in filenames:
                    if f.endswith(SOURCE_EXTS):
                        path = os.path.join(dirpath, f)
                        hits = cjk_literal_lines(path)
                        if hits:
                            kotlin_hits[rel(path)] = hits
    if list_hits:
        for path in sorted(kotlin_hits):
            out.write("%s  # %d\n" % (path, len(kotlin_hits[path])))
        return 0
    patterns = load_allowlist(allowlist_path)
    for path, hits in sorted(kotlin_hits.items()):
        if is_allowed(path, patterns):
            continue
        for line, text in hits:
            report.add(path, line, "cjk-literal", "string literal contains CJK text, move it to values/strings.xml (and values-en): %r" % text)
    # 白名单只能缩小
    for p in patterns:
        if not any(fnmatch.fnmatch(path, p) for path in kotlin_hits):
            report.add(rel(allowlist_path), 1, "stale-allowlist", "%s: matches no file with CJK literals any more, remove it from the allowlist" % p)
    report.print(out)
    n = len(report.errors)
    allowed = sum(len(h) for path, h in kotlin_hits.items() if is_allowed(path, patterns))
    out.write("check-i18n: %d resource dir(s) with values-en checked, %d allowlisted CJK literal(s) in %d file(s), %d error(s)\n" % (
        len(checked_res), allowed, sum(1 for p in kotlin_hits if is_allowed(p, patterns)), n))
    return 1 if n else 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=os.path.dirname(HERE), help="repository root (default: the parent of tools/)")
    ap.add_argument("--allowlist", default=None, help="allowlist file (default: tools/check-i18n.allowlist under --root)")
    ap.add_argument("--list-hits", action="store_true", help="list src/main files with CJK literals and exit")
    args = ap.parse_args(argv)
    allowlist = args.allowlist or os.path.join(args.root, "tools", "check-i18n.allowlist")
    return run(args.root, allowlist, args.list_hits)


if __name__ == "__main__":
    sys.exit(main())
