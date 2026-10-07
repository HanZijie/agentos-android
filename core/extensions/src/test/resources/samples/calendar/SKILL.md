---
name: calendar
description: >
  Create and look up events in the Calendar app: today's agenda, free slots,
  searching, moving or deleting events.
---

# Calendar

## Typical flow
1. `calendar_list` to find the calendar id (the default calendar cannot be deleted).
2. `agenda_today` or `event_list` with a `from` / `to` range to see what is planned.
3. `free_slots` before proposing a time; then `event_create`.

## Time format
All timestamps are ISO-8601 **with the time zone offset**, for example `2026-10-08T07:00:00+08:00`.
A date without an offset is rejected. All-day events use `allDay: true` and a date only.

## Pitfalls
- `event_delete` is destructive and cannot be undone; confirm with the user.
- Search with `event_search`; the `query` matches title and place.
