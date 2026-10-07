---
name: notes
description: Read, search and write the user's notes in the Notes app (Markdown notes with tags, colors, pinning, archive and trash). Use it when the user wants to jot something down, find or update a note, keep a checklist, tag or organize notes, or delete one.
---

# Notes (备忘录)

The Notes app keeps the user's notes on the phone. Every note has:

- `id` (string), `title`, `content` (**Markdown**), `tags` (list of strings), `color`, `pinned`
- a place: **active** (the main list), **archived**, or **trashed**. Trash is a safety net: nothing is lost until `note_delete`.
- `created_at` / `updated_at` as ISO-8601 with offset, e.g. `2026-10-08T07:00:00+08:00`

The user sees the app live: whatever you change appears on screen immediately, even in a note they have open.

## Tools

| Tool | Use it to |
|---|---|
| `note_search` | find notes by words in title, body or tags; returns a matching snippet |
| `note_list` | browse (active by default; `archived` / `trashed` for the other places); returns 200-character summaries and `has_more` / `next_offset` for paging |
| `note_get` | read one note in full (long bodies come in slices: follow `next_offset`) |
| `note_create` | add a note |
| `note_append` | add text to the end of an existing note |
| `note_update` | change title, body, tags, color, pinned, archived |
| `note_trash` / `note_restore` | move to / bring back from the trash (restore also un-archives) |
| `note_delete` | delete **permanently**; only works on notes already in the trash |
| `tag_list` | see the tags in use and how many notes each has |

## How to work well

1. **Search before you create.** For "remember that…", "add to my … list", "note down…": run `note_search` with the topic first. If a fitting note exists, use `note_append` (or `note_update`) instead of creating a duplicate. Create a new note only when nothing fits.
2. **`note_update` with `content` replaces the whole body.** To add something, use `note_append`. To rewrite, `note_get` first and send the complete new text. Never send a partial body.
3. **`tags` also replaces the whole list.** Read the current tags (`note_get`) and send the full new list. Look at `tag_list` first and reuse existing tags instead of inventing near-duplicates.
4. **Deleting:** default to `note_trash`. Call `note_delete` only when the user clearly wants a note gone for good, and only after it is in the trash. If unsure, ask.
5. After a change, tell the user which note you touched (title) and what changed; don't paste the whole note back.

## Writing the content

- Plain Markdown: `# Heading`, `**bold**`, `*italic*`, `` `code` ``, fenced code blocks, `> quote`, `- bullet`, `1. numbered`, `---` rule, `[text](https://…)`.
- Checklists: `- [ ] todo` and `- [x] done`. The app draws them as checkboxes with a progress bar. To tick an item, edit that line (`note_get`, then `note_update` with the full body) or append a new item with `note_append`.
- Keep the user's language. Short, scannable notes beat long prose. The first line is the title if you don't pass one.
- Tags: short words without `#` or commas, at most 32 characters, at most 20 per note; case-insensitive duplicates are merged.
- Colors: `default`, `yellow`, `orange`, `red`, `purple`, `blue`, `teal`, `green`, `gray`.

## Errors

A tool error is one plain sentence (unknown id, empty text, "move it to the trash first", too long…). Fix the call or tell the user; do not retry the same call blindly.
