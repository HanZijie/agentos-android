package org.agentos.sample.notes.data

/** 对仓库快照做筛选、排序、统计的纯函数，界面（直接用 StateFlow 里的列表）和工具层共用。 */
object NoteQueries {
    /** 置顶在前，其次按更新时间新到旧；回收站按删除时间新到旧。 */
    fun displayOrder(status: NoteStatus): Comparator<Note> = when (status) {
        NoteStatus.TRASHED -> compareByDescending<Note> { it.trashedAt ?: it.updatedAt }.thenByDescending { it.updatedAt }
        else -> compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt }.thenByDescending { it.createdAt }
    }.thenBy { it.id }

    fun filter(
        notes: List<Note>,
        status: NoteStatus,
        tag: String? = null,
        pinned: Boolean? = null,
    ): List<Note> {
        val tagKey = tag?.let { NoteText.tagKey(it) }?.takeIf { it.isNotEmpty() }
        return notes.asSequence()
            .filter { it.status == status }
            .filter { pinned == null || it.pinned == pinned }
            .filter { tagKey == null || it.tags.any { t -> NoteText.tagKey(t) == tagKey } }
            .sortedWith(displayOrder(status))
            .toList()
    }

    /** 标签及备忘录数：统计未进回收站的备忘录（正常 + 归档）；按数量降序，同数量按名字。 */
    fun tagCounts(notes: List<Note>, statuses: Set<NoteStatus> = setOf(NoteStatus.ACTIVE, NoteStatus.ARCHIVED)): List<TagCount> {
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for (note in notes) {
            if (note.status !in statuses) continue
            for (tag in note.tags) {
                val key = NoteText.tagKey(tag)
                val prev = counts[key]
                counts[key] = if (prev == null) tag to 1 else prev.first to (prev.second + 1)
            }
        }
        return counts.values
            .map { TagCount(it.first, it.second) }
            .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.name.lowercase() })
    }
}
