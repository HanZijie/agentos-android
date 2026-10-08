package org.agentos.sample.notes.agentos

import android.content.Context

/**
 * debug：默认用真网关；`DebugCallReceiver` 的 `cmd=fake_gateway --es script <名字>` 打开假网关开关（存在 SharedPreferences 里，
 * 按钮和 `ask_agent` 都读它），`--es script off` 关掉。release 的同名对象没有这个开关。
 */
object GatewayProvider {
    private const val PREFS = "notes_debug_agentos"
    private const val KEY = "fake_script"

    fun create(context: Context): AgentOsGateway {
        val name = currentScript(context)
        val script = name?.let { FakeScripts.byName(it) }
        return if (script != null) FakeAgentOsGateway(script) else createRealGateway(context)
    }

    fun currentScript(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    /** [name] 为 null 或 "off" 关掉开关；不认识的名字返回 false。 */
    fun setScript(context: Context, name: String?): Boolean {
        val off = name == null || name == "off"
        if (!off && name !in FakeScripts.names) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (off) remove(KEY) else putString(KEY, name)
        }.apply()
        return true
    }
}
