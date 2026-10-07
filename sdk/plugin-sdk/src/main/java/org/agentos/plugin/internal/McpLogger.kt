package org.agentos.plugin.internal

import android.util.Log

/** 日志出口。会话类不直接用 android.util.Log，所以能在 JVM 单元测试里跑。日志里只有方法名、长度和计数，没有参数和结果内容。 */
internal fun interface McpLogger {
    fun log(level: Char, message: String)

    companion object {
        val NONE = McpLogger { _, _ -> }
    }
}

internal object AndroidMcpLog : McpLogger {
    private const val TAG = "AgentOsMcp"

    override fun log(level: Char, message: String) {
        when (level) {
            'W' -> Log.w(TAG, message)
            'E' -> Log.e(TAG, message)
            'I' -> Log.i(TAG, message)
            else -> Log.d(TAG, message)
        }
    }
}
