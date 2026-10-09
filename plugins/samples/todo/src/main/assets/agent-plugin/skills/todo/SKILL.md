---
name: todo
description: Manage the user's to-do list in the Todo app - add, find, finish, re-prioritise, date, tag, split into subtasks, or delete things to do ("add a todo", "what's due this week", "mark the report done", "write three PRDs next week"). Anything with a clear done state belongs here. Drives the Todo app on this phone; the user sees changes live.
---

# Todo (待办)

The Todo app keeps the user's to-do list on the phone. A todo has:

- `id` (string), `title` (required, max 200 chars), `notes` (free text)
- `status`: `todo` (not started), `doing` (in progress), `done`, `shelved` (on hold). Done todos get `completed_at`.
- `priority`: `high`, `medium` (default), `low`
- `due`: optional, a date or a moment (see Formats). `due_all_day` says which. `overdue` is true when an open todo is past its due time.
- `tags` (list of short strings) and `parent_id` (set on a subtask). Only **one level** of subtasks exists: a subtask cannot have subtasks.

The user watches the list live; whatever you change shows up on screen at once.

## Tools

| Tool | Use it to |
|---|---|
| `todo_search` | find a todo by words in title, notes or tags (all statuses, done included) |
| `todo_list` | browse; hides done unless `include_done`; filters `status`, `priority`, `tag`, `due_before`, `due_after`, `overdue_only`, `parent_id`; pages with `limit` / `offset` / `has_more` |
| `todo_get` | read one todo in full, with its subtasks |
| `todo_create` | add a todo (or a subtask with `parent_id`) |
| `todo_update` | change title, notes, priority, due, tags, parent, status; only pass what changes |
| `todo_set_status` | move to `todo` / `doing` / `done` / `shelved` |
| `todo_delete` | delete for good, subtasks included (the user is asked to confirm) |
| `todo_summary` | counts per status plus `overdue`, `due_today`, `due_this_week`; the cheapest "how am I doing" call |

## Formats (get these right)

- `due` is **either** a date `"2026-10-12"` (an all-day due) **or** a date-time **with a UTC offset** `"2026-10-12T17:00:00+08:00"`. A date-time without an offset (`"2026-10-12T17:00"`) is rejected: use the phone's offset, which results show (`2026-...+08:00`). Convert "next Friday" or "tomorrow 3pm" to an absolute date yourself, using today's date and the user's time zone.
- `due_all_day: true` turns a date-time into an all-day due on that date. `due: ""` removes the due date.
- `todo_list` and `todo_search` items are compact: a field that is empty is simply absent (no `due` / `tags` / `parent_id` / `completed_at` = none; `overdue` appears only when true). `todo_get`, `todo_create`, `todo_update` and `todo_set_status` return every field.
- Results give `due` in the phone's offset (all-day: just the date). Tell the user dates in plain words ("Friday, 12 October"), not the raw string.
- `notes: ""` clears notes. `tags` replaces the whole list; read the current tags first when adding one. `parent_id: ""` detaches a subtask.
- `todo_list` bounds `due_before` / `due_after` are inclusive and accept a date or a date-time; all-day todos and date bounds compare by the phone's local calendar date. "Due this week" = `due_after` the first day of the week and `due_before` its last day (or just read `due_this_week` in `todo_summary`).
- `todo_summary`: `overdue` = past its due time; `due_today` = due today, not yet overdue; `due_this_week` = due from today to the end of the calendar week, not yet overdue (so it includes `due_today`). Only `todo` / `doing` todos count; it also returns `week_start`, `week_end`, `time_zone`.

## What belongs here

- **A thing with a clear done state goes in the Todo app** ("write the PRD", "renew passport", "call the plumber").
- Pure information to remember (ideas, a recipe, meeting minutes) is a note, not a todo.
- Something that occupies a time slot ("review meeting Wed 3-4pm") is a calendar event. Waking the user at a clock time is an alarm.
- **One thing = one item.** Do not create the same thing as a todo and a note. If a todo needs steps, add subtasks to it; do not create a second parallel todo.
- **This app does not remind.** A due date only sorts and highlights; nothing rings or notifies. If the user wants to be reminded, arrange it through the calendar (event `reminder_minutes`) or an alarm instead of promising it here.

## Typical flows

- **"Add three PRDs to do next week"** -> `todo_search` for the topic first, then `todo_create` three times with a `due` date each (all-day dates are fine), e.g. `{"title":"Write PRD: search","due":"2026-10-14","priority":"high"}`.
- **"Break the launch down"** -> `todo_create` the parent, then one `todo_create` per step with its `parent_id`.
- **"I finished the report"** -> `todo_search {"query":"report"}`, then `todo_set_status {"id":"...","status":"done"}`. If two todos match, ask which one.
- **"What's on my plate?"** -> `todo_summary`, then `todo_list` (with `overdue_only` or a due range) for the items.
- **"Push it to Friday"** -> `todo_update {"id":"...","due":"2026-10-16"}`; **"drop the due date"** -> `due: ""`.
- **"Park this for now"** -> `todo_set_status` `shelved` (it stays, quietly). Delete only when the user clearly wants it gone.

## Gotchas

- Completing a parent does not complete its subtasks, and deleting a parent deletes its subtasks (the result's `deleted` counts them all). Check with the user before deleting a todo that has `subtask_total` > 0.
- `todo_update` that changes nothing is fine and returns the todo unchanged; `todo_set_status` to the current status is a no-op.
- A todo that already has subtasks cannot be moved under another todo, and nothing can be put under a subtask.
- Errors come back as `isError` with one sentence (bad `due`, unknown id, missing title, subtask rules). Fix the argument; do not repeat the same call.
