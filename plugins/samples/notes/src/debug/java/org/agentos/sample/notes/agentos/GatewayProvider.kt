package org.agentos.sample.notes.agentos

import android.content.Context
import java.time.ZonedDateTime
import java.util.Locale

/**
 * debug：默认用真网关；`DebugCallReceiver` 的 `cmd=fake_gateway --es script <名字>` 打开假网关开关（存在 SharedPreferences 里，
 * 按钮和 `ask_agent` 都读它），`--es script off` 关掉。release 的同名对象没有这个开关。
 */
object GatewayProvider {
    private const val PREFS = "notes_debug_agentos"
    private const val KEY = "fake_script"
    private const val KEY_RAW = "raw_prompt"

    fun create(context: Context): AgentOsGateway {
        val name = currentScript(context)
        val script = name?.let { FakeScripts.byName(it) }
        return if (script != null) FakeAgentOsGateway(script) else createRealGateway(context)
    }

    fun currentScript(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    /**
     * 只给设备上的自动化测试用：打开后，发给 AgentOS 的提示词就是备忘文字本身（不加任务说明和安全说明），这样才能用测试假模型的
     * JSON 脚本（以 `{` 开头，见 tests/device/acp-channel/fake_model.py）驱动真 SDK 走完工具调用。默认关；release 里没有这个开关。
     */
    fun promptFor(context: Context, text: String, now: ZonedDateTime, locale: Locale): String =
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RAW, false)) text
        else NoteSchedulePrompt.build(text, now, locale)

    fun setRawPrompt(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RAW, on).apply()
    }

    fun rawPrompt(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RAW, false)

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
