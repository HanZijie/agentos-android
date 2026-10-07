#!/usr/bin/env python3
"""真机上经 ExtensionDebugReceiver 对三个示例 App 做完整增删改查，并逐步核对。只用 adb。"""
import json, re, subprocess, sys

S = "38290DLJH0007B"
ADB = ["adb", "-s", S, "shell"]
steps = []


def bc(op, **kw):
    extra = ""
    for k, v in kw.items():
        if isinstance(v, bool):
            extra += f" --ez {k} {'true' if v else 'false'}"
        elif isinstance(v, int):
            extra += f" --el {k} {v}"
        else:
            extra += f" --es {k} '{v}'"
    cmd = f"am broadcast -n org.agentos.app/.ext.ExtensionDebugReceiver --es op {op}{extra}"
    out = subprocess.run(ADB + [cmd], capture_output=True, text=True, timeout=60).stdout
    m = re.search(r'data="(.*)"\s*$', out.strip(), re.S)
    if not m:
        return {"ok": False, "error": "no result data", "raw": out[-200:]}
    return json.loads(m.group(1).replace("\\/", "/"))


def call(name, args, timeout=20000):
    r = bc("call", name=name, args=json.dumps(args, ensure_ascii=False), timeoutMs=timeout)
    oc = (r.get("outcome") or {})
    res = oc.get("result") or {}
    text = ""
    for c in res.get("content") or []:
        if c.get("type") == "text":
            text += c.get("text", "")
    data = res.get("details")
    return {"outcome": oc.get("outcome"), "isError": res.get("isError"), "text": text, "data": data, "ms": r.get("elapsedMs")}


def check(label, ok, detail=""):
    steps.append((label, bool(ok), detail))
    print(f"  {'ok ' if ok else 'FAIL'} {label}  {str(detail)[:150]}")


def n(plugin, tool):
    return f"mcp__{plugin}__{plugin}__{tool}"


def dump_db(pkg, db, sql):
    out = subprocess.run(ADB + [f"run-as {pkg} sqlite3 databases/{db} \"{sql}\" 2>&1"], capture_output=True, text=True).stdout.strip()
    return out


what = sys.argv[1] if len(sys.argv) > 1 else "all"

if what in ("calendar", "all"):
    print("== calendar")
    r = call(n("calendar", "calendar_list"), {})
    check("calendar_list", r["outcome"] == "completed" and not r["isError"], r["text"][:120])
    cals = (r["data"] or {}).get("calendars") or []
    default_cal = next((c for c in cals if c.get("is_default")), cals[0] if cals else None)
    check("has default calendar", default_cal is not None, default_cal and default_cal.get("name"))
    r = call(n("calendar", "event_create"), {"title": "MCP 真机会议", "start": "2026-10-14T15:00:00+08:00", "end": "2026-10-14T16:00:00+08:00",
                                              "location": "3 号会议室", "description": "经 AgentOS MCP 创建", "reminder_minutes": [15]})
    check("event_create", r["outcome"] == "completed" and not r["isError"], r["text"][:140])
    eid = (r["data"] or {}).get("id")
    check("event_create returns id", bool(eid), eid)
    r = call(n("calendar", "event_get"), {"id": eid})
    check("event_get title matches", (r["data"] or {}).get("title") == "MCP 真机会议", r["text"][:100])
    r = call(n("calendar", "event_list"), {"from": "2026-10-14T00:00:00+08:00", "to": "2026-10-15T00:00:00+08:00"})
    evs = (r["data"] or {}).get("events") or []
    check("event_list finds it", any(e.get("id") == eid for e in evs), f"{len(evs)} events")
    r = call(n("calendar", "event_update"), {"id": eid, "title": "MCP 真机会议（已改）", "location": "5 号会议室"})
    check("event_update", r["outcome"] == "completed" and not r["isError"], r["text"][:120])
    r = call(n("calendar", "event_search"), {"query": "已改"})
    check("event_search finds updated", any(e.get("id") == eid for e in (r["data"] or {}).get("events") or []), r["text"][:100])
    r = call(n("calendar", "free_slots"), {"date": "2026-10-14", "duration_minutes": 60})
    slots = (r["data"] or {}).get("slots") or []
    busy_overlap = any(s.get("start", "").startswith("2026-10-14T15") for s in slots)
    check("free_slots excludes 15:00-16:00", r["outcome"] == "completed" and not busy_overlap, f"{len(slots)} slots")
    r = call(n("calendar", "event_create"), {"title": "MCP 每周例会", "start": "2026-10-12T10:00:00+08:00", "recurrence": "weekly"})
    sid = (r["data"] or {}).get("id")
    check("event_create weekly", r["outcome"] == "completed" and not r["isError"], sid)
    r = call(n("calendar", "event_list"), {"from": "2026-10-12T00:00:00+08:00", "to": "2026-11-03T00:00:00+08:00", "query": "每周例会"})
    occ = (r["data"] or {}).get("events") or []
    check("weekly expands to >=3 occurrences", len(occ) >= 3, f"{len(occ)} occurrences")
    r = call(n("calendar", "event_get"), {"id": "does-not-exist"})
    check("event_get unknown id -> isError", r["isError"] is True, r["text"][:100])
    r = call(n("calendar", "event_create"), {"title": "缺开始时间"})
    check("event_create missing start -> isError", r["isError"] is True, r["text"][:100])
    r = call(n("calendar", "event_delete"), {"id": eid})
    check("event_delete", r["outcome"] == "completed" and not r["isError"], r["text"][:100])
    r = call(n("calendar", "event_get"), {"id": eid})
    check("event gone after delete", r["isError"] is True, r["text"][:80])
    call(n("calendar", "event_delete"), {"id": sid})

if what in ("notes", "all"):
    print("== notes")
    r = call(n("notes", "note_create"), {"content": "# 新品发布\n\n- 确认文案\n- 准备演示", "title": "新品发布备忘", "tags": ["工作"], "pinned": True})
    check("note_create", r["outcome"] == "completed" and not r["isError"], r["text"][:120])
    nid = (r["data"] or {}).get("id")
    check("note_create returns id", bool(nid), nid)
    r = call(n("notes", "note_append"), {"id": nid, "text": "- 发布日期定在周五"})
    check("note_append", r["outcome"] == "completed" and not r["isError"], r["text"][:100])
    r = call(n("notes", "note_get"), {"id": nid})
    check("note_get has appended text", "发布日期定在周五" in ((r["data"] or {}).get("content") or r["text"]), "")
    r = call(n("notes", "note_search"), {"query": "发布日期"})
    check("note_search hits", any(x.get("id") == nid for x in (r["data"] or {}).get("notes") or (r["data"] or {}).get("results") or []), r["text"][:100])
    r = call(n("notes", "note_update"), {"id": nid, "tags": ["工作", "紧急"]})
    check("note_update tags", r["outcome"] == "completed" and not r["isError"], r["text"][:100])
    r = call(n("notes", "tag_list"), {})
    tags = json.dumps(r["data"], ensure_ascii=False)
    check("tag_list has 紧急", "紧急" in tags, tags[:100])
    r = call(n("notes", "note_delete"), {"id": nid})
    check("note_delete refuses non-trashed", r["isError"] is True, r["text"][:120])
    r = call(n("notes", "note_trash"), {"id": nid})
    check("note_trash", r["outcome"] == "completed" and not r["isError"], r["text"][:100])
    r = call(n("notes", "note_restore"), {"id": nid})
    check("note_restore", r["outcome"] == "completed" and not r["isError"], r["text"][:100])
    call(n("notes", "note_trash"), {"id": nid})
    r = call(n("notes", "note_delete"), {"id": nid})
    check("note_delete after trash", r["outcome"] == "completed" and not r["isError"], r["text"][:100])
    r = call(n("notes", "note_get"), {"id": nid})
    check("note gone", r["isError"] is True, r["text"][:80])

if what in ("alarm", "all"):
    print("== alarm")
    r = call(n("alarm", "alarm_list"), {})
    existing = (r["data"] or {}).get("alarms") or []
    aid = next((a["id"] for a in existing if a.get("label") == "MCP真机测试"), None)
    check("earlier alarm still listed", aid is not None, f"{len(existing)} alarms")
    r = call(n("alarm", "alarm_update"), {"id": aid, "time": "07:45", "label": "MCP真机测试（已改）"})
    check("alarm_update", r["outcome"] == "completed" and not r["isError"], (r["data"] or {}).get("next_fire_at"))
    r = call(n("alarm", "alarm_set_enabled"), {"id": aid, "enabled": False})
    check("alarm_set_enabled false", r["outcome"] == "completed" and (r["data"] or {}).get("enabled") is False, r["text"][:100])
    r = call(n("alarm", "alarm_next"), {})
    check("alarm_next with none enabled", r["outcome"] == "completed" and not r["isError"], r["text"][:100])
    r = call(n("alarm", "alarm_set_enabled"), {"id": aid, "enabled": True})
    check("alarm_set_enabled true", r["outcome"] == "completed" and (r["data"] or {}).get("enabled") is True, "")
    r = call(n("alarm", "alarm_create"), {"time": "25:99"})
    check("alarm_create invalid time -> isError", r["isError"] is True, r["text"][:100])
    r = call(n("alarm", "alarm_get"), {"id": "nope"})
    check("alarm_get unknown id -> isError", r["isError"] is True, r["text"][:100])
    r = call(n("alarm", "alarm_delete"), {"id": aid})
    check("alarm_delete", r["outcome"] == "completed" and not r["isError"], r["text"][:100])
    r = call(n("alarm", "alarm_list"), {})
    check("alarm gone from list", not any(a["id"] == aid for a in (r["data"] or {}).get("alarms") or []), "")

fails = [s for s in steps if not s[1]]
print(f"\n{len(steps) - len(fails)}/{len(steps)} ok")
sys.exit(1 if fails else 0)
