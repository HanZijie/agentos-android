package org.agentos.test.acp.inapp

import org.agentos.test.acp.ChannelScenarios
import org.agentos.test.acp.ScenarioActivityBase
import org.json.JSONObject

/** 以 AgentOS 自己的 UID 对真正的 :agent 跑场景：通道场景（ChannelScenarios）+ AgentService 场景。 */
class AgentScenarioActivity : ScenarioActivityBase() {
    override suspend fun runScenario(name: String, args: JSONObject, runId: String, status: (String) -> Unit): JSONObject? =
        AgentServiceScenarios(this, work, status).run(name, args)
            ?: ChannelScenarios(this, work, runId, AppTarget(this), status).run(name, args)
}
