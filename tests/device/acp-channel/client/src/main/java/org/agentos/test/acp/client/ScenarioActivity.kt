package org.agentos.test.acp.client

import org.agentos.test.acp.ChannelScenarios
import org.agentos.test.acp.ProbeTarget
import org.agentos.test.acp.ScenarioActivityBase
import org.json.JSONObject

/** 第三方身份的场景执行器：通道场景都对测试 Agent 跑；foreign-open 对 AgentOS 跑。 */
class ScenarioActivity : ScenarioActivityBase() {
    override suspend fun runScenario(name: String, args: JSONObject, runId: String, status: (String) -> Unit): JSONObject? =
        ChannelScenarios(this, work, runId, ProbeTarget(this), status).run(name, args)
}
