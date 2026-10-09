"""tools/package-module.py 里 apksigner 输出解析的单元测试（不需要 Android SDK）。
运行：python3 -m unittest discover -s tools/tests -p 'test_*.py' -v

背景：CI 的 runner 上最新的 build-tools 是 37.0.0，它的 `apksigner verify --print-certs` 把
"Signer #1 certificate SHA-256 digest: ..." 改成了 "V2 Signer: certificate SHA-256 digest: ..."，
解析不出来就把已经签好名的 debug APK 当成“未签名”。
"""
import importlib.util
import os
import subprocess
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.join(HERE, "..")
spec = importlib.util.spec_from_file_location("package_module", os.path.join(TOOLS, "package-module.py"))
pm = importlib.util.module_from_spec(spec)
sys.modules["package_module"] = pm
spec.loader.exec_module(pm)

SHA = "792031b2d2b24ca18e1089ef66102a9d5841b99ebf4cd8e40001b81c12397843"
SHA2 = "43dcbd5bc753b7667b64ba9dd505d309dfbafba91ad6741155dbbd485c4cf681"

# build-tools 35 / 36
OLD = f"""Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: {SHA}
Signer #1 certificate SHA-1 digest: 3cba50fd839251aa0362b13580f070270743c894
Signer #1 certificate MD5 digest: 897cccc7d50c1f95e2beb10b788cf6c8
"""

# build-tools 37（GitHub runner 上抓到的原样输出）
NEW = f"""V2 Signer: certificate DN: C=US, O=Android, CN=Android Debug
V2 Signer: certificate SHA-256 digest: {SHA2}
V2 Signer: certificate SHA-1 digest: 4fc244a4bb1892fd8814d58f5c5ef699ba2aa2fb
V2 Signer: certificate MD5 digest: af29a481c7d23f85323187bccbec7393
"""


class ParseApksignerCert(unittest.TestCase):
    def test_old_format(self):
        self.assertEqual(pm.parse_apksigner_cert(OLD), SHA)

    def test_build_tools_37_format(self):
        self.assertEqual(pm.parse_apksigner_cert(NEW), SHA2)

    def test_v1_and_v3_signers_are_recognised_too(self):
        for scheme in ("V1", "V3", "V4"):
            out = f"{scheme} Signer: certificate SHA-256 digest: {SHA}\n"
            self.assertEqual(pm.parse_apksigner_cert(out), SHA, scheme)

    def test_first_signer_wins(self):
        out = NEW + f"V3 Signer: certificate SHA-256 digest: {SHA}\n"
        self.assertEqual(pm.parse_apksigner_cert(out), SHA2)

    def test_unsigned_or_unrelated_output_is_none(self):
        self.assertIsNone(pm.parse_apksigner_cert(""))
        self.assertIsNone(pm.parse_apksigner_cert("DOES NOT VERIFY\n"))
        # SHA-1 / MD5 行不能被当成 SHA-256
        self.assertIsNone(pm.parse_apksigner_cert("V2 Signer: certificate SHA-1 digest: 4fc244a4bb1892fd8814d58f5c5ef699ba2aa2fb\n"))
        # 摘要长度不对
        self.assertIsNone(pm.parse_apksigner_cert("V2 Signer: certificate SHA-256 digest: abcd\n"))


class CheckDeviceSed(unittest.TestCase):
    """tools/check-device.sh 里用 sed 取同一个值；用脚本里的同一条 sed 表达式验证两种格式。"""

    @classmethod
    def setUpClass(cls):
        with open(os.path.join(TOOLS, "check-device.sh"), encoding="utf-8") as f:
            line = next((l for l in f if "apksigner" in l and "sed -n" in l), None)
        cls.expr = None
        if line:
            start = line.index("sed -n")
            cls.expr = line[start:line.rindex(")")]  # "sed -n -E '...' | head -n 1"

    def run_sed(self, text):
        self.assertIsNotNone(self.expr, "the apksigner sed line moved in check-device.sh")
        r = subprocess.run(["sh", "-c", f"cat | {self.expr}"], input=text, capture_output=True, text=True)
        return r.stdout.strip()

    def test_old_format(self):
        self.assertEqual(self.run_sed(OLD), SHA)

    def test_build_tools_37_format(self):
        self.assertEqual(self.run_sed(NEW), SHA2)


if __name__ == "__main__":
    unittest.main()
