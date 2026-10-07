package org.agentos.sample.calendar.ui

/** 周视图里同一天内互相重叠的日程并排显示：给每个日程分配“第几列 / 共几列”。 */
object WeekLayout {
    data class Lane(val index: Int, val count: Int)

    /**
     * @param spans 每个日程在当天的 [开始分钟, 结束分钟)，已按需要放大到最小显示高度。
     * @return 与输入同序的列分配；互相（传递地）重叠的日程属于同一簇，共用该簇的列数。
     */
    fun lanes(spans: List<Pair<Int, Int>>): List<Lane> {
        if (spans.isEmpty()) return emptyList()
        val order = spans.indices.sortedWith(compareBy({ spans[it].first }, { -spans[it].second }, { it }))
        val result = arrayOfNulls<Lane>(spans.size)
        var cluster = ArrayList<Int>()
        var clusterEnd = Int.MIN_VALUE
        val laneEnds = ArrayList<Int>()
        val laneOf = IntArray(spans.size)

        fun flush() {
            for (i in cluster) result[i] = Lane(laneOf[i], laneEnds.size)
            cluster = ArrayList()
            laneEnds.clear()
            clusterEnd = Int.MIN_VALUE
        }

        for (i in order) {
            val (start, end) = spans[i]
            if (cluster.isNotEmpty() && start >= clusterEnd) flush()
            var lane = laneEnds.indexOfFirst { it <= start }
            if (lane < 0) {
                lane = laneEnds.size
                laneEnds.add(end)
            } else {
                laneEnds[lane] = end
            }
            laneOf[i] = lane
            cluster.add(i)
            clusterEnd = maxOf(clusterEnd, end)
        }
        flush()
        return result.map { it!! }
    }
}
