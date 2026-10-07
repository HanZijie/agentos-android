---
name: calendar
description: Read and manage the user's calendars and events in the Calendar app on this phone (list, create, update, delete, search events, today's agenda, free time slots). Use for scheduling, reminders-with-a-time, "what's on my calendar", "find a free hour", "move/cancel my meeting".
---

# Calendar

The phone's Calendar app keeps its own calendars and events. Everything you change here shows up in the app immediately, and reminders are scheduled by the app (a notification pops up before the event).

## Tools

| Tool | Use it to |
|---|---|
| `agenda_today` | See today's events. Also tells you today's **date and time zone**. |
| `event_list` | List events in a time range (recurring events are expanded, one item per occurrence). |
| `event_get` | Read one event by id. |
| `event_search` | Find events by words in title / location / notes, across all time. |
| `event_create` | Add an event. |
| `event_update` | Change fields of an existing event (only the fields you pass). |
| `event_delete` | Delete an event permanently. |
| `free_slots` | Find free time on one day for a meeting of a given length. |
| `calendar_list` / `calendar_create` / `calendar_update` / `calendar_delete` | Manage the calendars that events belong to. |

## Time format (read this first)

- Every time is **ISO-8601 with a UTC offset**: `2026-10-08T15:00:00+08:00`. A time **without** an offset is read in the phone's time zone, so `2026-10-08T15:00:00` is fine when the user means local time.
- You must turn relative phrases into absolute times **yourself** before calling a tool: "tomorrow 3pm", "next Wednesday afternoon", "in two hours". If you do not know today's date or the time zone, call `agenda_today` first (it returns `date` and `timezone`), then compute from that. Never pass words like "tomorrow" as a time.
- When the user gives only a time of day ("at 3pm"), assume the next occurrence of that time; when the date is ambiguous, ask.
- `end` defaults to start + 1 hour. For "a 30-minute call" pass `end` explicitly.
- All-day events: `all_day: true` with date-only values (`start: "2026-10-08"`). `end` is the **last day, inclusive**; omit it for a single day. Reminders on all-day events count back from 09:00 of the first day.
- Results use the same format, shifted to the phone's time zone; all-day events show 00:00 to 23:59:59.

## Typical flows

**Schedule something** ("book a meeting with Wang tomorrow 3pm"):
1. Compute the absolute start (and end) from today's date.
2. `event_list` for that day to check for a conflict. If one exists, tell the user and offer another time (`free_slots` finds gaps) instead of double-booking silently.
3. `event_create` with `title`, `start`, `end`, plus `location` / `reminder_minutes` when given. Use `reminder_minutes: [10]` when the user says "remind me" without a lead time.
4. Report what you created in the user's words (day, time, title).

**Find a time** ("when am I free for an hour on Friday?"): `free_slots` with `date` and `duration_minutes` (default window 09:00-18:00; pass `day_start` / `day_end` for other hours). It already counts recurring events and events that cross midnight.

**Move or rename**: `event_search` (or `event_list` for a day) to find the id, then `event_update` with only the changed fields. Changing `start` alone keeps the duration.

**Cancel**: find it with `event_search` / `event_list`. If several events match, show them and ask which one. Then `event_delete`. Deleting is permanent.

**Repeating events**: `recurrence` is `daily`, `weekly` (same weekday as `start`), `monthly` (same day of month; short months use the last day) or `yearly`, optionally with `recurrence_until` (inclusive). For "every Monday and Thursday" create two events. `event_update` and `event_delete` act on the **whole series**, even when you pass an occurrence id (`<series_id>@<key>`). To stop a series, update `recurrence_until` or delete it.

**Calendars**: events go to the default calendar unless `calendar_id` is given (see `calendar_list`). `calendar_delete` removes the calendar **and all its events**: confirm with the user first. `calendar_update` can hide a calendar in the app UI; hidden calendars are still returned by these tools.

## Pitfalls

- Ids are strings; copy them exactly from tool results. `id` of a recurring occurrence looks like `abc@20261008T010000Z`.
- `event_list` returns at most 200 items and sets `truncated: true` when there are more: narrow the range.
- Errors come back as one sentence (`isError`). Fix the argument and retry; do not retry blindly with the same input.
- `reminder_minutes` are minutes **before** the start (0 = at start), at most 5 values.
- Do not invent events or ids; if a search finds nothing, say so.
