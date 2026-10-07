package org.agentos.test.acp.inapp

import android.content.Context
import org.agentos.test.acp.ChannelScenarios
import org.agentos.test.acp.ScenarioActivityBase
import org.json.JSONObject

/**
 * 以 AgentOS 自己的 UID 对真正的 :agent 跑场景：通道场景（ChannelScenarios）+ AgentService 场景 + BYOK / Store 场景
 * + Pi 端到端场景（PiScenarios）。
 */
class AgentScenarioActivity : ScenarioActivityBase() {
    override suspend fun runScenario(name: String, args: JSONObject, runId: String, status: (String) -> Unit): JSONObject? {
        // BYOK 和真实对话用例自己管理模型来源；其他用例都要一个可用的模型，否则宿主层以 model_not_configured 拒绝任务
        if (!name.startsWith("byok-") && !name.startsWith("live-")) ensureTestModel(this)
        return AgentServiceScenarios(this, work, status).run(name, args)
            ?: JevScenarios(this, work, status).run(name, args)
            ?: ByokStoreScenarios(this, work, runId, status).run(name, args)
            ?: PiScenarios(this, work, status).run(name, args)
            ?: LiveModelScenarios(this).run(name, args)
            ?: ChannelScenarios(this, work, runId, AppTarget(this), status).run(name, args)
    }
}

/** 电脑上的假模型端点（tests/device/acp-channel/fake_model.py），经 adb reverse 映射到设备的这个端口。 */
object FakeModel {
    const val BASE_URL = "http://127.0.0.1:18787"
    const val MODEL = "fake-model"

    /** 假端点认的测试 key（不是任何真实 key）。 */
    const val KEY = "agtest-fake-model-key"

    fun source(): String = JSONObject().put("kind", "custom").put("api", "anthropic-messages")
        .put("baseUrl", BASE_URL).put("model", MODEL).toString()
}

/**
 * 通道和生命周期用例用的模型来源：电脑上的假模型端点（Pi 真的去请求它，按 prompt 里的脚本流式出字）。
 * 已经是它且可用时不改。
 */
suspend fun ensureTestModel(ctx: Context) {
    ControlClient(ctx).use { p ->
        val src = p.modelSource()
        if (!src.optBoolean("usable") || src.optString("baseUrl") != FakeModel.BASE_URL || src.optString("model") != FakeModel.MODEL) {
            p.setModelSource(FakeModel.source(), FakeModel.KEY)
        }
    }
}
