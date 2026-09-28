#!/usr/bin/env python3
"""把 run_s3.py 的结果 JSON 汇总成 Markdown 表，供 docs/spikes/S3.md 使用。

    python3 tools/summarize.py results/*.json
"""
import json
import sys

CASES = [
    ("handshake", "握手：initialize → session/new → prompt → close"),
    ("stream-realtime", "流式 100 条/秒"),
    ("stream-peak-bp", "流式打满（带生产者背压）"),
    ("stream-bigchunks-bp", "流式大块（每条 16,000 字符）"),
    ("stream-cjk-bp", "流式中文"),
    ("cancel-realtime", "流式中途 cancel"),
    ("cancel-peak-bp", "打满时 cancel"),
    ("reconnect", "close 后重新 bind × 20，无残留"),
    ("reconnect-noclose", "不调 close 直接 dispose × 5，无残留"),
    ("server-kill", "流式中途杀 :agent"),
    ("client-kill", "流式中途杀客户端"),
    ("oversize", "超长消息（四种情况）"),
    ("window-violation", "绕过流控塞消息"),
    ("desktop", "电脑端 adb forward + 官方 TS 客户端"),
]


def load(paths):
    runs = []
    for p in paths:
        d = json.load(open(p))
        dev = d["device"]
        label = f"API {dev['sdk']} {d['build']}" + (" (emu)" if dev.get("isEmulator") else f" ({dev['model']})")
        runs.append((label, d))
    return runs


def cell(name, r):
    if r is None:
        return "超时"
    ok = "✅" if r.get("ok") else "❌"
    extra = ""
    pr = r.get("prompt") or r.get("cancelled")
    if name.startswith("stream") and isinstance(pr, dict):
        lat = pr.get("latency", {})
        extra = f" {pr.get('chunksPerSec', 0):,.0f}/s p50 {lat.get('p50ms', 0):.1f} ms"
    elif name.startswith("cancel"):
        extra = f" {r.get('cancelToStopMs', -1):.0f} ms"
    elif name == "server-kill":
        extra = f" 感知 {r.get('detectMs', -1):.0f} ms，重建 {r.get('serviceRestartMs', -1):.0f} ms"
    elif name == "client-kill":
        d = r.get("detectMs")
        extra = f" 感知 {d:.0f} ms" if d is not None else ""
    elif name == "handshake":
        t = r.get("timingsMs", {})
        extra = f" bind {t.get('bound', 0):.0f} / init {t.get('initialized', 0):.0f} ms"
    return ok + extra


def main():
    runs = load(sys.argv[1:])
    print("| 用例 | " + " | ".join(l for l, _ in runs) + " |")
    print("|---|" + "---|" * len(runs))
    for name, title in CASES:
        row = [cell(name, d["results"].get(name)) if name in d["results"] else "—" for _, d in runs]
        print(f"| {title} | " + " | ".join(row) + " |")
    print()
    print("| Binder 边界 | " + " | ".join(l for l, _ in runs) + " |")
    print("|---|" + "---|" * len(runs))
    rows = {"单条上限 String（字符 / 载荷字节）": [], "单条上限 byte[]（字节）": [], "慢接收方连发失败时在途（1 KiB 条）": [],
            "原始 oneway 吞吐（64 字符）": []}
    for _, d in runs:
        R = d["results"]
        bs = R.get("bench-single", {})
        s = bs.get("STRING", {})
        b = bs.get("BYTES", {})
        rows["单条上限 String（字符 / 载荷字节）"].append(f"{s.get('maxOkUnits', '—'):,} / {s.get('maxOkParcelBytes', '—'):,}" if s else "—")
        rows["单条上限 byte[]（字节）"].append(f"{b.get('maxOkUnits', 0):,}" if b else "—")
        bb = R.get("bench-burst", {}).get("clientToAgent", [])
        one = next((x for x in bb if x.get("units") == 1024), None)
        rows["慢接收方连发失败时在途（1 KiB 条）"].append(
            f"第 {one['failAt']} 条，{one['inFlightBytesAtStop']:,} B" if one else "—")
        tp = R.get("bench-throughput", {}).get("results", [])
        t64 = next((x for x in tp if x.get("units") == 64), None)
        rows["原始 oneway 吞吐（64 字符）"].append(f"{t64['msgsPerSec']:,.0f}/s" if t64 else "—")
    for k, v in rows.items():
        print(f"| {k} | " + " | ".join(v) + " |")


if __name__ == "__main__":
    main()
