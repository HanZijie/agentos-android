#!/usr/bin/env bash
# 演示脚本：备忘录里一键让 AgentOS 安排日程 / 待办 / 闹钟（Pixel 8，真实模型 MiniMax-M3，手机直连；四个示例插件的写级工具设为“始终允许”，所以创建时不弹确认框）
#
# 用法：
#   tools/demo/notes_to_calendar.sh prepare   # 录屏前：把手机恢复到“干净的首次体验”状态，并放好演示用的备忘
#   tools/demo/notes_to_calendar.sh check     # 录屏前：检查一遍，全部打勾才开始录
#   tools/demo/notes_to_calendar.sh result    # 录完之后：把日历和闹钟里实际多出来的东西读出来，给你对照画面
#   tools/demo/notes_to_calendar.sh restore   # 录完之后：还原（关插件、清授权记录、清示例 App 数据）
#   tools/demo/notes_to_calendar.sh script    # 只打印演示台词和操作步骤
#
# 备忘里有三类事：下周写三个 PRD（有明确完成状态 → 待办）、明天和王总开会（占用时间段 → 日历，提前提醒写在日程里）、每周一跑步（到点叫醒 → 闹钟）。
# 只用 adb 准备和还原；演示过程中的每一次点击都是你亲手做的，脚本不碰屏幕。
# 脚本不读、不写任何 key（模型和 Jev 已经配在手机上，这里只检查）。
set -u
SERIAL="${SERIAL:-38290DLJH0007B}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$PATH:$ANDROID_HOME/platform-tools"
unset HTTPS_PROXY HTTP_PROXY https_proxy http_proxy

A="adb -s $SERIAL"
AGENTOS=org.agentos.app
NOTES=org.agentos.sample.notes
ALARM_R="org.agentos.sample.alarm/.debug.DebugToolReceiver"
TODO_R="org.agentos.sample.todo/.debug.DebugToolReceiver"
CAL_R="org.agentos.sample.calendar/.debug.DebugReceiver"
NOTES_R="$NOTES/.debug.DebugCallReceiver"
EXT_R="$AGENTOS/.ext.ExtensionDebugReceiver"
CALLERS_R="$AGENTOS/.agent.AcpCallerDebugReceiver"
CONSENT_R="$AGENTOS/.agent.ConsentDebugReceiver"
GW_R="$AGENTOS/.agent.DesktopGatewayDebugReceiver"

# 演示用的备忘：标题 + 正文（里面有一个会议和一个每周重复的晨跑；日期用“明天”“每周一”，所以任何一天录都对）
NOTE_TITLE="本周安排"
NOTE_BODY='明天下午3点和王总开会，3号会议室，提前15分钟提醒我；下周要写三个PRD：搜索、推荐、账号；每周一早上7点跑步。'

bc() { $A shell am broadcast -f 32 -n "$@" 2>&1 | grep -o 'data=.*'; }
say() { printf '%s\n' "$*"; }
ok() { printf '  [OK]   %s\n' "$*"; }
bad() { printf '  [NO]   %s\n' "$*"; BAD=1; }

# 在设备上发 JSON 参数时，adb shell 会把引号吃掉：用 python 组装并转义
py_bc() { python3 - "$SERIAL" "$@" <<'PY'
import sys, subprocess, json
serial, comp = sys.argv[1], sys.argv[2]
kv = sys.argv[3:]
parts = ["am", "broadcast", "-f", "32", "-n", comp]
for i in range(0, len(kv), 2):
    parts += ["--es", kv[i], "'" + kv[i + 1].replace("'", "'\\''") + "'"]
out = subprocess.run(["adb", "-s", serial, "shell", " ".join(parts)], capture_output=True, text=True).stdout
print(out.strip()[-600:])
PY
}

cmd_script() {
cat <<'EOF'
==================== 演示台词与步骤（约 1 分钟，一口气走完） ====================

开场（桌面）：
  “这是一个普通的备忘录 App。它自己不会安排日程，也没有联网。
   我想让手机上的 AI 助手 AgentOS，替我把备忘里的时间变成日程和闹钟。”

1. 打开「备忘录」→ 点进置顶的『本周安排』这条备忘（写着：明天下午 3 点和王总开会……每周一早上 7 点跑步）。
2. 点右上角的 ✨「让 AgentOS 安排」。
   → 底部弹出面板，先给你看将要发送的文字。说：“发出去之前，我先能看到它要拿走哪些文字。只有我点了才会发。”
3. 点「开始」。
   → 第一次使用：面板写着“请在 AgentOS 的提示里允许备忘录”。说：“备忘录第一次用 AgentOS，必须我自己同意。”
4. 点面板上的「打开提示」→ AgentOS 弹出“允许「Notes」使用 AgentOS 吗？”，里面有 App 名、包名和签名摘要。
   → 点「允许」。说：“这一次同意，以后不会再问，随时可以在 AgentOS 设置里撤销。”
   （这是整个流程里你唯一要点的授权。日历、待办和闹钟我已经提前在 AgentOS 里设成“始终允许”，所以下面创建时不会再弹确认。）
5. 回到备忘录面板（点返回或最近任务切回来）：AgentOS 的文字一行行流出来，下面出现五张卡：
   “日程：和王总开会”“待办：写 PRD（搜索 / 推荐 / 账号）×3”“闹钟：跑步”，状态从“创建中”变成“已创建”。
   说：“有明确完成状态的事进待办，占用时间的进日历，到点叫醒的才是闹钟，一件事只建一个。”
6. 顶部写“已创建 1 个日程、3 个待办、1 个闹钟”，出现「在日历中查看」「在待办中查看」「在闹钟中查看」。
   （没装或没启用待办 App 时，待办类的事留在备忘里，面板会说明；这是降级路径，不算失败。）
7. 点「在日历中查看」→ 日历里明天 15:00 的『和王总开会』，3 号会议室，提前 15 分钟提醒。
   返回，点「在待办中查看」→ 三条“写 PRD”待办，带截止日期。
   返回，点「在闹钟中查看」→ 每周一 07:00 的闹钟。
   说：“日程、待办和闹钟是真的建在各自的 App 里的。”

可选的加分镜头：
  A. 撤销：AgentOS → 设置 → 已授权的应用 → 备忘录 → 撤销。再回备忘录点开始，会被拒绝。
  B. 高风险仍要问：在 AgentOS 里让它“删除备忘录”之类的操作，仍然每次弹确认（高风险不能设成始终允许）。

录屏前别忘了：开勿扰、关通知预览（别的 App 的通知会入镜）。
EOF
}

cmd_prepare() {
  say "== 准备：把手机恢复到干净的首次体验状态"
  # 1) 日历、闹钟、待办、备忘录四个 App 的数据清空（日历只清自己创建的日程）
  bc "$CAL_R" --es cmd reset >/dev/null; bc "$ALARM_R" --es cmd reset >/dev/null; bc "$TODO_R" --es cmd reset >/dev/null; bc "$NOTES_R" --es cmd reset >/dev/null
  ok "日历、闹钟、待办、备忘录的数据已清空（备忘录会重新放入示例备忘）"
  # 2) 授权记录清空：这样第一次点「开始」会弹授权提示（演示的重点之一）
  bc "$CALLERS_R" --es op clear >/dev/null
  ok "AgentOS 里的“已授权的应用”已清空（第一次使用会弹授权）"
  # 3) 四个示例插件打开，并把它们所有“写”级工具设为“始终允许”（用户的决定：演示要一口气走完，不在中间弹确认框）。
  #    高风险工具（alarm_delete / calendar_delete / event_delete / note_delete / todo_delete）按规则不能设为“始终允许”，保持“每次确认”。
  for p in alarm calendar notes todo; do bc "$EXT_R" --es op enable --es id org.agentos.sample.$p >/dev/null; done
  sleep 4
  python3 - "$SERIAL" <<'PY'
import sys, subprocess, re, json
S = sys.argv[1]
EXT = "org.agentos.app/.ext.ExtensionDebugReceiver"
def b(*a):
    out = subprocess.run(["adb","-s",S,"shell","am","broadcast","-f","32","-n",EXT]+list(a),capture_output=True,text=True).stdout
    m = re.search(r'data="(.*)"\s*$', out.strip(), re.S)
    return json.loads(m.group(1).replace("\\/","/")) if m else {}
changed = 0
for p in ("alarm", "calendar", "notes", "todo"):
    for t in b("--es","op","tools","--es","id","org.agentos.sample." + p).get("tools", []):
        if t["risk"] != "high" and t["mayAlwaysAllow"] and t["approval"] != "always":
            r = b("--es","op","tool_approval","--es","name",t["name"],"--es","mode","always")
            changed += 1 if r.get("ok") else 0
print("  [OK]   四个插件的写级工具：始终允许（这次改了 %d 个；其余本来就是）" % changed)
PY
  # 4) 确认框用真实界面（不是自动应答）
  bc "$CONSENT_R" --es op mode --es mode off >/dev/null
  ok "确认框模式：真实界面（不自动应答）"
  # 5) 放好演示用的备忘
  py_bc "$NOTES_R" tool note_create args "{\"title\":\"$NOTE_TITLE\",\"content\":\"$NOTE_BODY\",\"pinned\":true}" >/dev/null
  ok "已放入演示备忘『${NOTE_TITLE}』（置顶）"
  # 6) 回到桌面，亮屏
  $A shell input keyevent KEYCODE_WAKEUP; $A shell input keyevent KEYCODE_HOME
  say ""
  say "准备完成。下一步：  $0 check"
}

cmd_check() {
  BAD=0
  say "== 录屏前检查"
  $A get-state >/dev/null 2>&1 && ok "手机在线（${SERIAL}）" || { bad "手机不在线"; exit 1; }
  # 屏幕和锁屏
  $A shell dumpsys power | grep -q "mWakefulness=Awake" && ok "屏幕亮着" || bad "屏幕是灭的：先亮屏并解锁（PIN 自己输）"
  $A shell dumpsys window | grep -q "mDreamingLockscreen=true\|isKeyguardShowing=true\|mShowingLockscreen=true" && bad "手机还在锁屏：先解锁" || ok "已解锁"
  # 应用
  for p in $AGENTOS $NOTES org.agentos.sample.alarm org.agentos.sample.calendar org.agentos.sample.todo; do
    $A shell pm path $p >/dev/null 2>&1 && ok "已安装 $p" || bad "没装 $p"
  done
  # 模型：真实 MiniMax-M3，手机直连
  MS="$(bc "$GW_R" --es op status)"
  echo "$MS" | grep -q 'modelUsable\\\?":true' && echo "$MS" | grep -q 'api.minimaxi.com' && echo "$MS" | grep -q 'MiniMax-M3' && ok "模型：MiniMax-M3（api.minimaxi.com，手机直连）" || bad "模型源不对：$(echo "$MS" | head -c 200)"
  # 网络：能连模型端点
  $A shell "ping -c 1 -W 3 api.minimaxi.com" >/dev/null 2>&1 && ok "手机能连到 api.minimaxi.com" || bad "手机连不上 api.minimaxi.com（WLAN 是否可用？）"
  # 授权记录应为空（否则看不到“第一次使用要同意”）
  CL="$(bc "$CALLERS_R" --es op list)"
  echo "$CL" | grep -q '"callers":\[\]' && ok "“已授权的应用”是空的（第一次使用会弹授权）" || bad "授权记录不是空的：跑 prepare"
  # 四个插件都启用；写级工具都是“始终允许”（不弹确认框），高风险的是“每次确认”
  python3 - "$SERIAL" <<'PY'
import sys, subprocess, re, json
S = sys.argv[1]
EXT = "org.agentos.app/.ext.ExtensionDebugReceiver"
def b(*a):
    out = subprocess.run(["adb","-s",S,"shell","am","broadcast","-f","32","-n",EXT]+list(a),capture_output=True,text=True).stdout
    m = re.search(r'data="(.*)"\s*$', out.strip(), re.S)
    return json.loads(m.group(1).replace("\\/","/")) if m else {}
plugins = {p["id"]: p for p in b("--es","op","list").get("plugins", [])}
bad = 0
for name in ("alarm", "calendar", "notes", "todo"):
    pid = "org.agentos.sample." + name + "/agent-plugin"
    p = plugins.get(pid)
    if not p or not p.get("enabled"):
        print("  [NO]   %s 插件没启用：跑 prepare" % name); bad += 1; continue
    ts = b("--es","op","tools","--es","id","org.agentos.sample." + name).get("tools", [])
    wrong = [t["name"].split("__")[-1] for t in ts if t["enabled"] and ((t["risk"] != "high" and t["approval"] != "always") or (t["risk"] == "high" and t["approval"] != "ask"))]
    if not ts or wrong:
        print("  [NO]   %s：%d 个工具，审批设置不对的：%s（跑 prepare）" % (name, len(ts), wrong)); bad += 1
    else:
        nh = sum(1 for t in ts if t["risk"] == "high")
        print("  [OK]   %s 插件已启用：%d 个工具，写级始终允许，%d 个高风险每次确认" % (name, len(ts), nh))
sys.exit(1 if bad else 0)
PY
  [ $? = 0 ] || BAD=1
  # 没有残留的确认卡
  PEND="$(bc "$CONSENT_R" --es op pending)"
  echo "$PEND" | grep -q '"pending":\[\]' && ok "没有残留的确认卡" || bad "有残留的确认卡：跑 prepare"
  # 备忘存在
  $A shell am broadcast -f 32 -n "$NOTES_R" --es cmd dump 2>&1 | grep -q "$NOTE_TITLE" && ok "演示备忘在" || bad "找不到演示备忘：跑 prepare"
  # 通知权限（后台时授权提示和确认走通知）
  $A shell dumpsys package $AGENTOS | grep -q "POST_NOTIFICATIONS: granted=true" && ok "AgentOS 有通知权限" || bad "AgentOS 没有通知权限（后台时看不到确认通知）"
  say ""
  if [ "$BAD" = 0 ]; then say "全部就绪，可以开始录屏。台词与步骤：  $0 script"; else say "有 [NO] 项，先处理再录。"; exit 1; fi
}

cmd_result() {
  say "== 录完之后：日历、待办和闹钟里实际多出来的东西（对照你录到的画面）"
  python3 - "$SERIAL" <<'PY'
import sys, subprocess, re, json
S = sys.argv[1]
def b(comp, *a):
    out = subprocess.run(["adb","-s",S,"shell","am","broadcast","-f","32","-n",comp]+list(a),capture_output=True,text=True).stdout
    m = re.search(r'data="(.*)"\s*$', out.strip(), re.S)
    return json.loads(m.group(1).replace("\\/","/")) if m else {}
cal = b("org.agentos.sample.calendar/.debug.DebugReceiver","--es","cmd","dump","--ei","limit","50")
al = b("org.agentos.sample.alarm/.debug.DebugToolReceiver","--es","cmd","dump","--ei","limit","50")
td = b("org.agentos.sample.todo/.debug.DebugToolReceiver","--es","cmd","dump","--ei","limit","50")
print("日历：")
for e in cal.get("events", []):
    print("  - %s  %s  地点=%s  提前提醒=%s  重复=%s" % (e.get("title"), e.get("start"), e.get("location") or "-", e.get("reminder_minutes"), e.get("recurrence")))
if not cal.get("events"): print("  （没有日程）")
print("待办：")
for t in td.get("todos", []):
    print("  - %s  截止=%s  优先级=%s  状态=%s" % (t.get("title"), t.get("due") or "-", t.get("priority"), t.get("status")))
if not td.get("todos"): print("  （没有待办）")
print("闹钟：")
for a in al.get("alarms", []):
    print("  - %s  %s  重复=%s  开=%s" % (a.get("time"), a.get("label") or "-", a.get("days") or "仅一次", a.get("enabled")))
if not al.get("alarms"): print("  （没有闹钟）")
sch = (cal.get("reminders_scheduled") or [])
print("系统里已登记的日历提醒闹钟：", [(r.get("title"), r.get("fire_at"), r.get("registered")) for r in sch])
PY
  CL="$(bc "$CALLERS_R" --es op list)"
  echo "AgentOS 里的授权记录：$(echo "$CL" | grep -o '"label":"[^"]*","signingDigest":"[0-9a-f]\{12\}[^"]*","state":"[a-z]*"' | head -3 | sed 's/signingDigest":"\([0-9a-f]\{12\}\)[^"]*"/signingDigest":"\1…"/')"
}

cmd_restore() {
  say "== 还原"
  bc "$CONSENT_R" --es op mode --es mode off >/dev/null
  python3 - "$SERIAL" <<'PY'
import sys, subprocess, re, json
S = sys.argv[1]
def b(comp, *a):
    out = subprocess.run(["adb","-s",S,"shell","am","broadcast","-f","32","-n",comp]+list(a),capture_output=True,text=True).stdout
    m = re.search(r'data="(.*)"\s*$', out.strip(), re.S)
    return json.loads(m.group(1).replace("\\/","/")) if m else {}
C = "org.agentos.app/.agent.ConsentDebugReceiver"
for p in b(C,"--es","op","pending").get("pending",[]): b(C,"--es","op","respond","--es","id",p["requestId"],"--es","choice","DENY")
PY
  bc "$CALLERS_R" --es op clear >/dev/null; ok "授权记录已清空"
  # 审批方式：“始终允许”是用户对每个工具自己的设置，demo 之后不替用户保留。恢复成默认“每次确认”
  for p in alarm calendar notes todo; do bc "$EXT_R" --es op enable --es id org.agentos.sample.$p >/dev/null; done; sleep 3
  python3 - "$SERIAL" <<'PY'
import sys, subprocess, re, json
S = sys.argv[1]
EXT = "org.agentos.app/.ext.ExtensionDebugReceiver"
def b(*a):
    out = subprocess.run(["adb","-s",S,"shell","am","broadcast","-f","32","-n",EXT]+list(a),capture_output=True,text=True).stdout
    m = re.search(r'data="(.*)"\s*$', out.strip(), re.S)
    return json.loads(m.group(1).replace("\\/","/")) if m else {}
n = 0
for p in ("alarm", "calendar", "notes", "todo"):
    for t in b("--es","op","tools","--es","id","org.agentos.sample." + p).get("tools", []):
        if t["approval"] == "always":
            n += 1 if b("--es","op","tool_approval","--es","name",t["name"],"--es","mode","ask").get("ok") else 0
print("  [OK]   工具审批方式恢复成默认“每次确认”（改了 %d 个）" % n)
PY
  for p in alarm calendar notes todo; do bc "$EXT_R" --es op disable --es id org.agentos.sample.$p >/dev/null; done; ok "四个示例插件已关闭（回到默认关闭）"
  bc "$CAL_R" --es cmd reset >/dev/null; bc "$ALARM_R" --es cmd reset >/dev/null; bc "$TODO_R" --es cmd reset >/dev/null; bc "$NOTES_R" --es cmd reset >/dev/null; ok "四个示例 App 的数据已清空（日历只清了自己创建的日程）"
  say "模型和 Jev 的配置保留，没有动。"
}

case "${1:-}" in
  prepare) cmd_prepare ;;
  check) cmd_check ;;
  result) cmd_result ;;
  restore) cmd_restore ;;
  script) cmd_script ;;
  *) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
