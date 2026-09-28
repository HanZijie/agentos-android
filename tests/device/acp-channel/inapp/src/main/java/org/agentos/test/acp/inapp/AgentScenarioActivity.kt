package org.agentos.test.acp.inapp

import android.content.Context
import org.agentos.test.acp.ChannelScenarios
import org.agentos.test.acp.ScenarioActivityBase
import org.json.JSONObject

/** 以 AgentOS 自己的 UID 对真正的 :agent 跑场景：通道场景（ChannelScenarios）+ AgentService 场景 + BYOK / Store 场景。 */
class AgentScenarioActivity : ScenarioActivityBase() {
    override suspend fun runScenario(name: String, args: JSONObject, runId: String, status: (String) -> Unit): JSONObject? {
        // BYOK 用例自己管理模型来源；其他用例要求有一个可用的模型，否则宿主层以 model_not_configured 拒绝任务
        if (!name.startsWith("byok-")) ensureTestModel(this)
        return AgentServiceScenarios(this, work, status).run(name, args)
            ?: ByokStoreScenarios(this, work, runId, status).run(name, args)
            ?: ChannelScenarios(this, work, runId, AppTarget(this), status).run(name, args)
    }
}

/**
 * 通道和生命周期用例用的模型来源：回环地址上的自定义兼容端点。B2 之前 :agent 的 Agent core 是 ScriptedAgentCore，
 * 不调模型，这个端点和 key 都不会被用到；只是让宿主层的调度器认为“模型已配置”。已经可用时不改。
 */
suspend fun ensureTestModel(ctx: Context) {
    ControlClient(ctx).use { p ->
        if (!p.modelSource().optBoolean("usable")) {
            p.setModelSource(
                JSONObject().put("kind", "custom").put("api", "openai-completions")
                    .put("baseUrl", "http://127.0.0.1:9/v1").put("model", "agentos-device-test").toString(),
                "agtest-placeholder-key",
            )
        }
    }
}
