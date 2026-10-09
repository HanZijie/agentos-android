#!/usr/bin/env python3
"""Fill the phone's system SMS store with ~10 realistic demo messages for screen recording the SMS app's "Schedule with AgentOS" button.

The SMS app is not the default messaging app, so it cannot write messages itself. This needs a ROOTED phone (Magisk): the rows are
inserted with `su -c content insert content://sms/inbox`, exactly what the system itself does when a message arrives.

  seed   : remove the demo rows written by an earlier `seed` (only rows whose address is one of the demo addresses below), then insert
           the demo messages with timestamps counted back from NOW. Run it right before recording: the messages talk about "tomorrow" and about
           weekdays that are always a few days ahead, and AgentOS works those out from the date each message arrived, so fresh timestamps keep the
           resulting events in the future.
  clean  : remove the demo rows only.
  status : how many demo rows are on the phone.

Real messages are never touched: deletion is limited to the demo addresses (documentation/test numbers and made-up short codes).
Message text contains no secrets. The one verification code in it (482915) is made up.

Usage: sms_demo_seed.py <serial> seed|clean|status
"""
import datetime as dt
import os
import subprocess
import sys
import tempfile

SERIAL = sys.argv[1] if len(sys.argv) > 1 else ""
MODE = sys.argv[2] if len(sys.argv) > 2 else "status"
if not SERIAL or MODE not in ("seed", "clean", "status"):
    sys.exit(__doc__)

COLLEAGUE = "+8613800138000"   # China Mobile's documentation test number
MOTHER = "+8613912345678"
BANK = "95588"
LOCKER = "10690123"
AIRLINE = "95530"
CODE = "106900"
SHOP = "106980"
DEMO_ADDRESSES = [COLLEAGUE, MOTHER, BANK, LOCKER, AIRLINE, CODE, SHOP]


def md(day: dt.date) -> str:
    return "%d月%d日" % (day.month, day.day)


def weekday(day: dt.date) -> str:
    return "周" + "一二三四五六日"[day.weekday()]


def messages(now: dt.datetime):
    """(address, minutes ago, text). Oldest first in the conversation list order does not matter: the phone sorts by date."""
    today = now.date()
    pay_by = today + dt.timedelta(days=10)
    pick_by = today + dt.timedelta(days=3)
    flight = today + dt.timedelta(days=9)
    report_by = today + dt.timedelta(days=3)  # "by <weekday>" is always an upcoming day, never today
    dinner = today + dt.timedelta(days=2)
    return [
        (AIRLINE, 240, "【东方航空】您预订的 MU5137 航班 %s 08:20 由上海虹桥 T2 起飞，请提前 2 小时到机场办理值机。" % md(flight)),
        (BANK, 180, "【工商银行】您尾号 1234 的信用卡本期账单 3,280.50 元，最后还款日 %s，请及时还款。" % md(pay_by)),
        (LOCKER, 150, "【菜鸟驿站】您的包裹已到海淀西区驿站，取件码 7-3-2018，请于 %s 前凭码取件。" % md(pick_by)),
        (COLLEAGUE, 95, "明天上午 10 点产品评审会，3 号会议室，记得带原型。"),
        (COLLEAGUE, 90, "另外 Q3 数据报告%s之前发我一下。" % weekday(report_by)),
        (MOTHER, 70, "明天早上 6 点半记得起床，赶 8 点的高铁，别睡过头啊。"),
        (MOTHER, 40, "%s回家吃饭，我炖了排骨，中午 12 点开饭。" % weekday(dinner)),
        (CODE, 20, "【美团】验证码 482915，5 分钟内有效，请勿泄露给任何人。"),
        (COLLEAGUE, 12, "对了，评审会提前 15 分钟提醒大家到场。"),
        (SHOP, 300, "【京东】双 11 预热开启！全场满 299 减 100，回 T 退订。"),
    ]


def adb(*a, timeout=60):
    return subprocess.run(["adb", "-s", SERIAL] + list(a), capture_output=True, text=True, timeout=timeout)


def su(cmd: str) -> str:
    """Run `cmd` as root through a pushed script file: no quoting trouble with Chinese text or brackets."""
    with tempfile.NamedTemporaryFile("w", suffix=".sh", delete=False, encoding="utf-8") as f:
        f.write(cmd + "\n")
        local = f.name
    try:
        adb("push", local, "/data/local/tmp/sms_demo.sh")
        out = adb("shell", "su -c 'sh /data/local/tmp/sms_demo.sh'; rm -f /data/local/tmp/sms_demo.sh")
        return out.stdout + out.stderr
    finally:
        os.unlink(local)


def count_demo() -> int:
    where = " OR ".join("address='%s'" % a for a in DEMO_ADDRESSES)
    out = su("content query --uri content://sms --projection _id --where \"%s\"" % where)
    return sum(1 for line in out.splitlines() if line.startswith("Row:"))


def delete_demo() -> None:
    for a in DEMO_ADDRESSES:
        su("content delete --uri content://sms --where \"address='%s'\"" % a)


def main():
    if "uid=0" not in su("id"):
        sys.exit("this needs a rooted phone (su -c id did not return uid=0)")
    if MODE == "status":
        print("demo rows on the phone:", count_demo())
        return
    delete_demo()
    if MODE == "clean":
        print("demo rows left:", count_demo())
        return
    now = dt.datetime.now()
    now_ms = int(now.timestamp() * 1000)
    lines = []
    for address, minutes_ago, text in messages(now):
        assert "'" not in text and '"' not in text and "$" not in text, text
        ts = now_ms - minutes_ago * 60_000
        # `content insert` reads a binding as column:type:value, so a colon inside the text (08:20) has to be escaped
        lines.append("content insert --uri content://sms/inbox --bind address:s:%s --bind body:s:'%s' --bind date:l:%d --bind date_sent:l:%d --bind read:i:0 --bind seen:i:0"
                     % (address, text.replace(":", "\\:"), ts, ts))
    out = su("\n".join(lines))
    if "ERROR" in out or "usage:" in out:
        print(out[-600:])
        sys.exit("an insert was rejected")
    n = count_demo()
    print("inserted; demo rows on the phone: %d (expected %d)" % (n, len(lines)))
    if n != len(lines):
        sys.exit(1)


main()
