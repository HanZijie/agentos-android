package org.agentos.sample.notes

import android.content.Context
import org.agentos.sample.notes.data.NoteColor
import org.agentos.sample.notes.data.NoteDraft
import org.agentos.sample.notes.data.NoteException
import org.agentos.sample.notes.data.NoteRepository

/** 第一次运行时放进去的几条示例备忘录（跟随系统语言，覆盖 Markdown 各种写法、标签、颜色、置顶、任务进度）。 */
object Seeds {
    private const val PREF_SEEDED = "seeded_v1"

    suspend fun seedIfFirstRun(context: Context, repository: NoteRepository) {
        val prefs = NotesGraph.preferences()
        if (prefs.getBoolean(PREF_SEEDED, false)) return
        // 先标记再写：中途被杀也不会下次重复放
        prefs.edit().putBoolean(PREF_SEEDED, true).apply()
        if (repository.notes.value.isNotEmpty()) return
        // 倒序创建，让第一条（欢迎）成为最近更新的
        for (draft in drafts(context).asReversed()) {
            try {
                repository.create(draft)
            } catch (_: NoteException) {
            }
        }
    }

    fun drafts(context: Context): List<NoteDraft> {
        fun seed(title: Int, content: Int, tags: Int, color: NoteColor, pinned: Boolean = false) = NoteDraft(
            title = context.getString(title),
            content = context.getString(content),
            tags = context.getString(tags).split(','),
            color = color,
            pinned = pinned,
        )
        return listOf(
            seed(R.string.seed_1_title, R.string.seed_1_content, R.string.seed_1_tags, NoteColor.TEAL, pinned = true),
            seed(R.string.seed_3_title, R.string.seed_3_content, R.string.seed_3_tags, NoteColor.YELLOW, pinned = true),
            seed(R.string.seed_2_title, R.string.seed_2_content, R.string.seed_2_tags, NoteColor.BLUE),
            seed(R.string.seed_5_title, R.string.seed_5_content, R.string.seed_5_tags, NoteColor.ORANGE),
            seed(R.string.seed_4_title, R.string.seed_4_content, R.string.seed_4_tags, NoteColor.PURPLE),
            seed(R.string.seed_6_title, R.string.seed_6_content, R.string.seed_6_tags, NoteColor.GREEN),
            seed(R.string.seed_7_title, R.string.seed_7_content, R.string.seed_7_tags, NoteColor.DEFAULT),
        )
    }
}
