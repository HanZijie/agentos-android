---
name: calendar
description: Read and manage the user's calendars and events on this phone (list, create, update, delete, search events, today's agenda, free time slots). Covers the phone's account calendars (Google, CalDAV and others that sync to the cloud) and this app's own device-only calendars. Use for scheduling, reminders-with-a-time, "what's on my calendar", "find a free hour", "move/cancel my meeting".
---

# Calendar

This phone has two kinds of calendars, and every tool works on both:

- **Account / system calendars** live in the phone's calendar database (`storage: "system"`). If the user signed in to Google, a CalDAV account (Feishu, DAVx5, ...) or another account, those calendars show up here and **the system syncs what you write to the cloud**. Other calendar apps on the phone see the same events.
- **Device-only calendars of this app** (`storage: "app"`, `source: "local"`) are not synced anywhere. They always exist, even without any account or permission.

Everything you change shows up in the Calendar app immediately. Reminders for account/system calendars are delivered by the phone's own Calendar app; reminders for this app's calendars are delivered by this app.

## Tools

| Tool | Use it to |
|---|---|
| `agenda_today` | See today's events. Also tells you today's **date and time zone**. |
| `event_list` | List events in a time range (recurring events are expanded, one item per occurrence). |
| `event_get` | Read one event by id. |
| `event_search` | Find events by words in title / location / notes (optionally in one calendar). |
| `event_create` | Add an event. |
| `event_update` | Change fields of an existing event (only the fields you pass). |
| `event_delete` | Delete an event permanently. |
| `free_slots` | Find free time on one day for a meeting of a given length. |
| `calendar_list` | List calendars with where they live (`account`, `source`, `writable`, `storage`) and which one is the default. |
| `calendar_create` / `calendar_update` / `calendar_delete` | Manage this app's own device-only calendars. |

## Time format (read this first)

- Every time is **ISO-8601 with a UTC offset**: `2026-10-08T15:00:00+08:00`. A time **without** an offset is read in the phone's time zone, so `2026-10-08T15:00:00` is fine when the user means local time.
- You must turn relative phrases into absolute times **yourself** before calling a tool: "tomorrow 3pm", "next Wednesday afternoon", "in two hours". If you do not know today's date or the time zone, call `agenda_today` first (it returns `date` and `timezone`), then compute from that. Never pass words like "tomorrow" as a time.
- When the user gives only a time of day ("at 3pm"), assume the next occurrence of that time; when the date is ambiguous, ask.
- `end` defaults to start + 1 hour. For "a 30-minute call" pass `end` explicitly.
- All-day events: `all_day: true` with date-only values (`start: "2026-10-08"`). `end` is the **last day, inclusive**; omit it for a single day. Reminders on all-day events count back from 09:00 of the first day.
- Results use the same format, shifted to the phone's time zone; all-day events show 00:00 to 23:59:59.

## Calendars, the default calendar, and what is writable

- Call `calendar_list` when the user names a calendar ("put it in my work calendar") or when you are unsure. Each calendar has an `id`, `name`, `account` (empty for device-only), `source` (`google`, `caldav`, `local`, or `other` for an account type this app does not recognise such as Exchange or a phone-maker account), `writable`, `storage`, and `is_default`.
- `event_create` without `calendar_id` writes to the **default write calendar** (`is_default: true` in `calendar_list`). The user can choose it in the app's settings; otherwise it is the first writable, visible account calendar, and if there is none, this app's own local calendar. Tell the user which calendar you used (the result has `calendar_name`).
- A calendar with `writable: false` (holidays, birthdays, shared read-only calendars) can be read but not written: `event_create`, `event_update` and `event_delete` fail for it. Pick another calendar.
- `calendar_create` only creates device-only calendars of this app; it cannot create a Google or CalDAV calendar. `calendar_delete` only deletes those too and refuses account calendars and the app's default calendar. `calendar_update` can rename/recolor device-only calendars; for account calendars it can only show/hide them in the app.
- An event cannot be moved between account calendars (or between an account calendar and the device-only ones): create it in the target calendar and delete the original.

## Typical flows

**Schedule something** ("book a meeting with Wang tomorrow 3pm"):
1. Compute the absolute start (and end) from today's date.
2. `event_list` for that day to check for a conflict. If one exists, tell the user and offer another time (`free_slots` finds gaps) instead of double-booking silently.
3. `event_create` with `title`, `start`, `end`, plus `location` / `reminder_minutes` when given. Use `reminder_minutes: [10]` when the user says "remind me" without a lead time.
4. Report what you created in the user's words (day, time, title) and the calendar it went to.

**Find a time** ("when am I free for an hour on Friday?"): `free_slots` with `date` and `duration_minutes` (default window 09:00-18:00; pass `day_start` / `day_end` for other hours). It counts recurring events, events that cross midnight and account-calendar events; events marked "free" or declined do not block.

**Move or rename**: `event_search` (or `event_list` for a day) to find the id, then `event_update` with only the changed fields. Changing `start` alone keeps the duration.

**Cancel**: find it with `event_search` / `event_list`. If several events match, show them and ask which one. Then `event_delete`. Deleting is permanent. **For an event in an account calendar (`source` google / caldav / other) the deletion is synced to the account and removes the event on the user's other devices, with no undo**: say which event and which account before you do it.

**Repeating events**: `recurrence` is `daily`, `weekly` (same weekday as `start`), `monthly` (same day of month; short months use the last day) or `yearly`, optionally with `recurrence_until` (inclusive). For "every Monday and Thursday" create two events. `event_update` and `event_delete` act on the **whole series**, even when you pass an occurrence id (`<series_id>@<key>`). To stop a series, update `recurrence_until` or delete it.

**Custom repeat rules** (events created by other apps or synced from the cloud): an event whose rule this app cannot express (for example "every second Tuesday", every 2 weeks, a fixed number of repeats) is returned with `recurrence: "custom"` and the original `rrule`. **You cannot change such an event**: `event_update` fails and nothing is modified, because rewriting the rule could break the series. Tell the user to edit it in the calendar app that owns it, or (only if they ask) delete it and create a new one. You can still read it and delete it. `custom` is output only; never pass it as an input.

## Permission

Account and system calendars need the Calendar permission (granted by the user in this app). When it is missing, every tool that touches them fails with exactly: `Calendar permission not granted; ask the user to grant it in the Calendar app`. Do not retry; tell the user to open the Calendar app and tap "Allow" (or enable the permission in system settings). The device-only calendars keep working without it: pass their `calendar_id` (from `calendar_list`) to `event_list`, `event_search`, `agenda_today` and `free_slots`; `event_create` without `calendar_id` then falls back to the local calendar.

## Pitfalls

- Ids are strings; copy them exactly from tool results. They carry a source prefix: `local:<uuid>` for this app's own calendars and events, `sys:<number>` for the phone's calendar database. An occurrence of a recurring event looks like `sys:42@20261008T010000Z`.
- `event_list` returns at most 200 items and sets `truncated: true` when there are more: narrow the range.
- Changes you write to account calendars are synced by the system when the phone has network access; they can take a while to appear on other devices or the web.
- Errors come back as one sentence (`isError`). Fix the argument and retry; do not retry blindly with the same input.
- `reminder_minutes` are minutes **before** the start (0 = at start), at most 5 values.
- Do not invent events or ids; if a search finds nothing, say so.
