package org.agentos.sample.notes.data

/** 存储抽象：仓库只通过它读写，JVM 测试用内存实现，App 里用 [SqliteNoteStore]。方法是阻塞的，由仓库放到 IO 线程调用。 */
interface NoteStore {
    fun loadAll(): List<Note>

    /** 插入或整条覆盖。 */
    fun save(note: Note)

    fun delete(ids: Collection<String>)
}
