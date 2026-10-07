package org.agentos.sample.notes.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.agentos.sample.notes.NotesGraph
import org.agentos.sample.notes.tools.ToolOutput

/**
 * debug 包专用：`adb shell am broadcast -n org.agentos.sample.notes/.debug.DebugCallReceiver --es tool note_list --es args '{"limit":3}'`
 * 直接调工具层（和 MCP 服务用同一个仓库对象），结果以一行 JSON 写到 logcat（tag NotesDebug）。
 */
class DebugCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tool = intent.getStringExtra("tool") ?: return
        val args = intent.getStringExtra("args") ?: "{}"
        val pending = goAsync()
        scope.launch {
            val line = try {
                val parsed = Json.parseToJsonElement(args) as? JsonObject ?: error("args must be a JSON object")
                when (val out = NotesGraph.tools.call(tool, parsed)) {
                    is ToolOutput.Ok -> """{"tool":"$tool","ok":true,"result":${out.value}}"""
                    is ToolOutput.Error -> """{"tool":"$tool","ok":false,"error":${Json.encodeToString(kotlinx.serialization.serializer<String>(), out.message)}}"""
                }
            } catch (e: Exception) {
                """{"tool":"$tool","ok":false,"error":${Json.encodeToString(kotlinx.serialization.serializer<String>(), e.message ?: e.javaClass.simpleName)}}"""
            }
            Log.i(TAG, line)
            pending.finish()
        }
    }

    private companion object {
        const val TAG = "NotesDebug"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
