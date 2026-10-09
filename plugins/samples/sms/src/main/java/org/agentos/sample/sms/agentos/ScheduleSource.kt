package org.agentos.sample.sms.agentos

import org.agentos.sample.sms.data.SmsBox
import org.agentos.sample.sms.data.SmsRecord
import org.agentos.sample.sms.rules.CodeMasker

/**
 * 一条要交给 AgentOS 的短信。[body] 是**短信原文**（没有经过任何 AI 处理），只按设置遮蔽验证码，规则和 MCP 工具里 Agent 看到的一样。
 * [processed]：之前已经让 AgentOS 处理过（见 [ProcessedLedger]）。
 */
data class SmsLine(
    val id: String,
    val dateMillis: Long,
    val incoming: Boolean,
    val body: String,
    val processed: Boolean = false,
) {
    companion object {
        /**
         * 系统短信库的行 → 候选列表（旧的在前，新的在后）。只要真正收到 / 发出的短信（收件箱、已发送）：草稿、发件箱、排队中、
         * 发送失败的不算；空正文跳过。[maskCodes] 为真时验证码按 [CodeMasker] 遮蔽。
         */
        fun from(records: List<SmsRecord>, maskCodes: Boolean, processedIds: Set<String>): List<SmsLine> =
            records
                .filter { (it.box == SmsBox.INBOX || it.box == SmsBox.SENT) && it.body.isNotBlank() }
                .sortedWith(compareBy<SmsRecord> { it.dateMillis }.thenBy { it.id.toLongOrNull() ?: Long.MAX_VALUE }.thenBy { it.id })
                .map {
                    SmsLine(
                        id = it.id,
                        dateMillis = it.dateMillis,
                        incoming = it.box == SmsBox.INBOX,
                        body = if (maskCodes) CodeMasker.mask(it.body).text else it.body,
                        processed = it.id in processedIds,
                    )
                }
    }
}

/**
 * 发给 AgentOS 的内容：一个会话里的短信（[candidates]，旧到新）、是否把已处理过的也带上（[includeProcessed]），
 * 以及用户可编辑的任务说明（[instructions]，提示词里唯一能改的部分，见 [SmsSchedulePrompt]）。
 */
data class ScheduleSource(
    val address: String,
    val candidates: List<SmsLine>,
    val includeProcessed: Boolean,
    val instructions: String,
) {
    /** 实际要发送的那几条（见 [ScheduleSelection]）。 */
    val selection: ScheduleSelection.Result = ScheduleSelection.of(candidates, includeProcessed)
    val lines: List<SmsLine> get() = selection.lines
    val hasText: Boolean get() = selection.lines.isNotEmpty()

    /** 候选里有多少条还没处理过。 */
    val unprocessedCount: Int get() = candidates.count { !it.processed }

    companion object {
        val NONE = ScheduleSource("", emptyList(), includeProcessed = false, instructions = "")
    }
}

/**
 * 从候选里挑出要发送的短信（纯函数）：默认只发还没处理过的；超出字数预算时留最新的、丢掉更早的（保持时间顺序）。
 * 预算按“正文 + 每条固定开销”算，和时区、语言无关。
 */
object ScheduleSelection {
    /** 短信数据部分的字数预算；加上任务说明（最多 [SmsSchedulePrompt.MAX_INSTRUCTION_CHARS]）和固定说明，仍在 AgentOS 的 16,000 字上限内。 */
    const val MAX_DATA_CHARS = 8_000

    /** 单条正文最多发送这么多字符（超出截断）。 */
    const val MAX_BODY_CHARS = 1_000

    /** 每条短信在提示词里的固定开销（时间、方向、缩进）的估计。 */
    const val LINE_OVERHEAD = 40

    data class Result(
        val lines: List<SmsLine>,
        /** 因为“已处理过”而没带上的条数。 */
        val skippedProcessed: Int,
        /** 因为超出字数预算而丢掉的更早的条数。 */
        val droppedOlder: Int,
    )

    fun of(candidates: List<SmsLine>, includeProcessed: Boolean): Result {
        val eligible = candidates.filter { includeProcessed || !it.processed }
        val kept = ArrayList<SmsLine>()
        var used = 0
        for (line in eligible.asReversed()) {
            // 按转义之后的长度算：正文里的 `<sms` 会变长，不能让它把提示词顶过 AgentOS 的上限
            val cost = SmsSchedulePrompt.escape(line.body.take(MAX_BODY_CHARS)).length + LINE_OVERHEAD
            if (kept.isNotEmpty() && used + cost > MAX_DATA_CHARS) break
            kept += line
            used += cost
        }
        kept.reverse()
        return Result(kept, skippedProcessed = candidates.size - eligible.size, droppedOlder = eligible.size - kept.size)
    }
}
