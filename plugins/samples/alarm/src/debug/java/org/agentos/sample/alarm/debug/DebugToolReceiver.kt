package org.agentos.sample.alarm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.agentos.sample.alarm.AlarmGraph

/**
 * 仅 debug 构建：直接调用工具层（与 MCP 服务注册的是同一批 [org.agentos.sample.alarm.tools.ToolDef]）。
 *
 *   adb shell am broadcast -n org.agentos.sample.alarm/.debug.DebugToolReceiver \
 *       --es tool alarm_create --es args '{"time":"09:30","label":"x"}'
 *
 * 结果写 logcat：`AlarmDebug: <tool> isError=<bool> <json>`。tool=list 打印全部工具名。
 */
class DebugToolReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("tool") ?: return
        val graph = AlarmGraph.get(context)
        if (name == "list") {
            Log.i(TAG, "tools: " + graph.tools.tools.joinToString(",") { it.name })
            return
        }
        val tool = graph.tools.find(name)
        if (tool == null) {
            Log.w(TAG, "$name isError=true unknown tool")
            return
        }
        val args = runCatching { Json.parseToJsonElement(intent.getStringExtra("args") ?: "{}") as JsonObject }
            .getOrElse {
                Log.w(TAG, "$name isError=true args is not a JSON object")
                return
            }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val out = tool.handler(args)
                Log.i(TAG, "$name isError=${out.isError} ${out.text}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AlarmDebug"
    }
}
