package org.agentos.app.ui.consent

import org.agentos.app.agent.consent.ConsentWire.Card

/**
 * 主进程里的待确认队列（纯 Kotlin，ConsentQueueTest）。事实在 `:agent` 的协调器里；这里只是它在前台界面上的镜像：
 * 按到达顺序（先进先出）排列，按 requestId 去重（登记监听者时的快照和随后的 onRequested 可能重复）。
 */
class ConsentQueue {
    private val items = LinkedHashMap<String, Card>()

    /** 队首：界面现在显示的那一条。 */
    val head: Card? get() = items.values.firstOrNull()

    val size: Int get() = items.size

    fun cards(): List<Card> = items.values.toList()

    /** 快照是 `:agent` 此刻的全部待确认：以它为准整体替换（保持快照里的顺序）。 */
    fun replaceAll(snapshot: List<Card>) {
        items.clear()
        snapshot.forEach { items.putIfAbsent(it.requestId, it) }
    }

    /** 新请求；已有同一个 requestId 的不改变位置（只更新内容）。 */
    fun add(card: Card) {
        items[card.requestId] = card
    }

    /** 请求结案（答复、超时、取消、关闭）。返回它原来是不是队首。 */
    fun remove(requestId: String): Boolean {
        val wasHead = head?.requestId == requestId
        items.remove(requestId)
        return wasHead
    }

    fun contains(requestId: String) = requestId in items

    fun clear() = items.clear()
}
