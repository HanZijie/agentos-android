---
name: alarm
description: Use when the user wants to set, change, turn on or off, remove or check alarms ("wake me at 7", "cancel tomorrow's alarm", "what alarms do I have", "when is my next alarm"). Drives the Alarm app on this phone; alarms created here really ring.
---

# Alarm

The Alarm app on this phone keeps the alarms. Every change you make is applied to the system alarm clock immediately and
shows up in the app's list at once. Alarms ring with sound, vibration and a full-screen page, even on the lock screen.

**Set alarms only with this app's tools.** Do not open another clock app or send a "set alarm" intent: two apps would both
ring. This app is the single source for alarms you create or change.

## Tools

| Tool | Use it to |
|---|---|
| `alarm_list` | See all alarms, soonest first (`enabled_only` hides switched-off ones). |
| `alarm_get` | Read one alarm by `id`. |
| `alarm_create` | Make a new alarm. Only `time` is required. |
| `alarm_update` | Change fields of an existing alarm; only pass what changes. |
| `alarm_set_enabled` | Switch an alarm on or off without losing it. |
| `alarm_delete` | Remove an alarm for good (destructive, asks the user to confirm). |
| `alarm_next` | Which alarm rings next, and in how many minutes. Returns `null` when nothing is on. |
| `alarm_dismiss` | Stop the alarm that is ringing right now. Errors if nothing is ringing. |
| `alarm_snooze` | Silence the ringing alarm and let it ring again after its snooze length. |
| `alarm_system_next` | Read-only. The next alarm of **any** alarm app on the phone (what the status bar shows), with `owned_by_this_app`. Returns `null` when no alarm is set anywhere. |

## Formats (get these right)

- `time` is **local 24-hour `"HH:mm"`**: `"07:30"`, `"18:05"`. "7 pm" is `"19:00"`, "midnight" is `"00:00"`. Never put a date in it.
- `days` is a list of `"mon" "tue" "wed" "thu" "fri" "sat" "sun"`. **Empty or missing = ring once** at the next time that
  clock reading comes round, then switch itself off. Weekdays = `["mon","tue","wed","thu","fri"]`, weekend = `["sat","sun"]`.
- `snooze_minutes` is 1-60 (default 10). `label` is short text (max 60 characters); `label: ""` clears it.
- `id` is a string. Copy it from `alarm_list` / `alarm_create`; do not invent it.
- Times in results are ISO-8601 with the phone's offset (`2026-10-08T07:00:00+08:00`). `next_fire_at` is when the alarm
  will actually ring (`null` while it is switched off). Tell the user the time in plain words ("tomorrow at 7:00"), not the raw string.

## Typical flows

- **"Wake me up at 7 tomorrow"** -> `alarm_create {"time":"07:00"}`; read `next_fire_at` back to the user so they can confirm it is the day they meant.
- **"Every weekday at 6:45, call it Run"** -> `alarm_create {"time":"06:45","days":["mon","tue","wed","thu","fri"],"label":"Run"}`.
- **"Move my 7 o'clock alarm to 7:30"** -> `alarm_list`, find the alarm whose `time` is `"07:00"`, then `alarm_update {"id":"...","time":"07:30"}`. If two alarms match, ask which one.
- **"Turn off the Monday alarm for now"** -> find it, `alarm_set_enabled {"id":"...","enabled":false}` (do not remove it).
- **"Remove all my alarms"** -> `alarm_list`, then confirm with the user before calling `alarm_delete` once per alarm.
- **"What's my next alarm?"** -> `alarm_next`.
- **"What time do I get up tomorrow?" / "Do I have an alarm?"** -> call both `alarm_next` (this app's own alarms) and
  `alarm_system_next` (the whole phone). If `alarm_system_next` is earlier and `owned_by_this_app` is `false`, another clock
  app set it: tell the user that alarm exists outside this app and that you cannot list, change or delete it.
- **"Remind me about the 3 pm meeting" / any reminder tied to an event** -> not an alarm. Put it on the calendar event's
  `reminder_minutes`. Create an alarm only when the user wants something to *ring at a clock time* ("wake me at 7"). One thing
  gets one entry, never both an event reminder and an alarm.

## Where things belong (across the Alarm, Calendar, Notes, Todo and Messages apps)

- A thing with a clear done state ("write the PRD", "renew passport") goes in the **Todo** app.
- Something that occupies a stretch of time ("review meeting Wed 3-4 pm") is a **calendar event**; a lead-time reminder for it goes in the event's `reminder_minutes`.
- Waking the user at a clock time ("wake me at 7") is an **alarm**. An event reminder is not an alarm.
- Pure information to remember (an idea, a recipe, meeting minutes) is a **note**.
- Telling someone else something ("text Wang the minutes") is a **message**; sending needs the user's approval every time.
- **One thing = one entry.** Never record the same thing as a todo and a note, or as an event reminder and an alarm. Use only the tools that are actually in your tool list; if an app is not installed or not enabled, keep the item where it fits best among the apps you do have and tell the user.

## Gotchas

- Look before you create: `alarm_list` first when the user may already have that alarm, so you do not make duplicates.
- `alarm_update` with `days` **replaces** the whole repeat set; to add Saturday to a weekday alarm pass all six days.
- Changing `time`, `days` or `enabled` ends any snooze in progress.
- A one-time alarm switches itself off after it rings; `alarm_set_enabled true` arms it again for the next occurrence.
- `alarm_dismiss` and `alarm_snooze` only work while an alarm is actually ringing; otherwise they return an error saying so.
- `alarm_system_next` is a mirror, not a handle: it has no id and nothing in it can be edited with the tools above.
- Errors come back as `isError` with one sentence (bad time format, unknown id, missing parameter). Fix the argument and retry; do not repeat the same call.
