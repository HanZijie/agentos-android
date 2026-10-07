package org.agentos.test.acp.inapp

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * sample_apps_e2e.py 的 `--live`：把手机的模型来源设成真实的 MiniMax（key 由电脑端经 KeyDropProvider 投递到 files/test/live_key，
 * 读完立即删除），对话由电脑端经 acp-bridge 发起；跑完用 `live-model-clear` 清除，key 不留在设备上。
 * 名字以 `live-` 开头，所以 AgentScenarioActivity 不会先把模型来源换成假端点。结果里没有 key。
 */
class LiveModelScenarios(private val ctx: Context) {
    suspend fun run(name: String, args: JSONObject): JSONObject? = when (name) {
        "live-model-set" -> set(args)
        "live-model-clear" -> clear()
        else -> null
    }

    private suspend fun set(args: JSONObject): JSONObject {
        val f = File(ctx.filesDir, args.optString("keyFile", "test/live_key"))
        val key = try {
            f.takeIf { it.isFile }?.readText()?.trim()
        } finally {
            f.delete()
        }
        if (key.isNullOrEmpty()) return JSONObject().put("ok", false).put("summary", "no live key was delivered")
        val provider = args.optString("provider", "minimax-cn")
        val model = ControlClient(ctx).use { p ->
            val models = p.presets(provider).getJSONObject("provider").getJSONArray("models")
            val wanted = args.optString("model")
            val ids = (0 until models.length()).map { models.getJSONObject(it).getString("id") }
            val id = ids.firstOrNull { it == wanted } ?: ids.first()
            p.setModelSource(JSONObject().put("kind", "preset").put("provider", provider).put("model", id).toString(), key)
            id
        }
        return JSONObject().put("ok", true).put("model", "$provider/$model").put("summary", "model source set: $provider/$model")
    }

    private suspend fun clear(): JSONObject {
        val configured = ControlClient(ctx).use { p ->
            p.clearModelSource()
            p.modelSource().optBoolean("configured")
        }
        return JSONObject().put("ok", !configured).put("summary", "model source cleared, configured=$configured")
    }
}
