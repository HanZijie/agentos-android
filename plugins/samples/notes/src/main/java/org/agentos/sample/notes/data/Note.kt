package org.agentos.sample.notes.data

/** 备忘录所处的位置：正常 / 归档 / 回收站。回收站里再删除才是永久删除。 */
enum class NoteStatus(val code: Int, val key: String) {
    ACTIVE(0, "active"),
    ARCHIVED(1, "archived"),
    TRASHED(2, "trashed");

    companion object {
        fun fromCode(code: Int): NoteStatus = entries.firstOrNull { it.code == code } ?: ACTIVE
    }
}

/** 备忘录的颜色标记。对外（MCP）用 [key]；界面里每个颜色有亮 / 暗两套实际色值（见 ui/theme）。 */
enum class NoteColor(val key: String) {
    DEFAULT("default"),
    YELLOW("yellow"),
    ORANGE("orange"),
    RED("red"),
    PURPLE("purple"),
    BLUE("blue"),
    TEAL("teal"),
    GREEN("green"),
    GRAY("gray");

    companion object {
        val keys: List<String> = entries.map { it.key }

        fun fromKey(key: String?): NoteColor? {
            if (key == null) return null
            return entries.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) }
        }
    }
}

/**
 * 一条备忘录。[revision] 每次真正改动都加一，界面编辑页靠它发现“别处（MCP）改过了”；
 * [title] 可以为空，显示时用 [displayTitle]（缺省取正文首行）。时间是 epoch 毫秒。
 */
data class Note(
    val id: String,
    val title: String,
    val content: String,
    val tags: List<String>,
    val color: NoteColor,
    val pinned: Boolean,
    val status: NoteStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val trashedAt: Long?,
    val revision: Long,
) {
    val isTrashed: Boolean get() = status == NoteStatus.TRASHED
    val isArchived: Boolean get() = status == NoteStatus.ARCHIVED

    /** 标题；没写标题时取正文首行（去掉 Markdown 前缀），都没有则为空串，由界面决定占位文字。 */
    val displayTitle: String get() = title.ifBlank { NoteText.deriveTitle(content) }
}

/** 新建时给的字段。 */
data class NoteDraft(
    val title: String = "",
    val content: String = "",
    val tags: List<String> = emptyList(),
    val color: NoteColor = NoteColor.DEFAULT,
    val pinned: Boolean = false,
    val status: NoteStatus = NoteStatus.ACTIVE,
)

/** 修改时只带要改的字段（null = 不改）；[tags] 是整体替换。 */
data class NotePatch(
    val title: String? = null,
    val content: String? = null,
    val tags: List<String>? = null,
    val color: NoteColor? = null,
    val pinned: Boolean? = null,
    val archived: Boolean? = null,
) {
    val isEmpty: Boolean get() = title == null && content == null && tags == null && color == null && pinned == null && archived == null
}

/** 全部标签及其备忘录数。 */
data class TagCount(val name: String, val count: Int)

object NoteLimits {
    const val MAX_TITLE_CHARS = 200
    const val MAX_CONTENT_CHARS = 200_000
    const val MAX_TAGS = 20
    const val MAX_TAG_CHARS = 32
}

/** 仓库操作失败的原因。工具层把它变成 isError 结果，界面把它变成提示。 */
class NoteException(val kind: Kind, message: String, val current: Note? = null) : Exception(message) {
    enum class Kind {
        /** 没有这个 id。 */
        NOT_FOUND,
        /** 参数不合法（太长、标签格式不对……）。 */
        INVALID,
        /** 当前状态不允许这个操作（比如永久删除不在回收站里的备忘录）。 */
        STATE,
        /** 期望的版本和当前版本不一致：别处刚改过。[current] 是最新版本。 */
        CONFLICT,
    }
}
