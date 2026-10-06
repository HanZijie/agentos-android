package org.agentos.runtime.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A9 的机理，确定性地复现：另一个线程刚把并发集合里唯一的元素移走时，`size` 还是 1、迭代器已经空了。
 * Kotlin 的 `toList()` / `sortedBy` 对大小 ≤ 1 的集合先读 `size` 再 `iterator().next()`，于是抛 NoSuchElementException；
 * [snapshot]（`ArrayList(collection)`，走 `toArray()`）不会。真实连接上的竞态见 DesktopGatewayCoreTest。
 */
class ConcurrentSnapshotTest {
    private val raced = object : java.util.AbstractCollection<Int>() {
        override val size: Int get() = 1

        override fun iterator(): MutableIterator<Int> = mutableListOf<Int>().iterator()
    }

    @Test
    fun `toList and sortedBy fail when the only element goes away in between, snapshot does not`() {
        assertFailsWith<NoSuchElementException> { raced.toList() }
        assertFailsWith<NoSuchElementException> { raced.sortedBy { it } }
        assertEquals(emptyList(), raced.snapshot())
        assertEquals(emptyList(), raced.snapshot().sortedBy { it })
    }
}
