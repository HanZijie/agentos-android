---
name: "notes"
description: 'Keep notes: create, append, search, tag, move to the trash and restore. Use it when the user wants to remember something.'
---

# Notes

## Typical flow
1. `note_search` first: appending to an existing note is better than creating a duplicate.
2. `note_create` with `title` and `content` (Markdown is fine), or `note_append` to add to the end.
3. `note_trash` moves a note to the trash; `note_restore` brings it back. `note_delete` only works on notes in the trash.

## Pitfalls
- `note_delete` is permanent. Trash first, then delete only when the user asked for it.
- Tags are plain words; list them with `tag_list`.
