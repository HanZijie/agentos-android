---
name: alarm
description: Set, list, change and delete alarms on this phone with the Alarm app. Use it for wake-up calls, reminders at a time of day and countdowns.
---

# Alarm

The Alarm app owns the phone's alarms. Always look before you change something.

## Typical flow
1. `alarm_list` to see what exists (check for a duplicate before creating one).
2. `alarm_create` with `time` as local `HH:mm` (24 hour clock, for example `07:30`), optional `label`, and `repeat` as a list of weekdays (`mon`..`sun`).
3. `alarm_update` / `alarm_set_enabled` to change or switch an alarm; `alarm_delete` removes it for good (ask the user first).

## Pitfalls
- Times are local `HH:mm`, not ISO timestamps. "Tomorrow 7am" is `07:30`-style plus no date.
- `alarm_next` tells you the next time any alarm will ring; use it to answer "when is my next alarm".
- Ids are strings; take them from `alarm_list`, never invent one.
