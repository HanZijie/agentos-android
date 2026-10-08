package org.agentos.test.acp.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import org.agentos.test.acp.ChannelScenarios
import org.agentos.test.acp.ProbeTarget
import org.agentos.test.acp.ScenarioActivityBase
import org.json.JSONObject

/** 第三方身份的场景执行器：通道场景都对测试 Agent 跑；foreign-open 对 AgentOS 跑。 */
class ScenarioActivity : ScenarioActivityBase() {
    override suspend fun runScenario(name: String, args: JSONObject, runId: String, status: (String) -> Unit): JSONObject? =
        if (name == "bind-mcp") bindMcp(args)
        else ThirdPartyScenarios(this, work, runId, status).run(name, args)
            ?: ChannelScenarios(this, work, runId, ProbeTarget(this), status).run(name, args)

    /**
     * C7b：第三方 App（没有 org.agentos.permission.BIND_MCP_SERVICE，那是 AgentOS 定义的 signature 权限）直接 bind 插件的 MCP 服务，
     * 系统必须拒绝（SecurityException）。插件包对本 App 可见（Manifest 的 queries），所以 false / 异常不是可见性造成的。
     */
    private fun bindMcp(args: JSONObject): JSONObject {
        val component = ComponentName(args.getString("pkg"), args.getString("service"))
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val visible = runCatching { packageManager.getPackageInfo(component.packageName, 0) }.isSuccess
        var bound = false
        var error: String? = null
        try {
            bound = bindService(Intent().setComponent(component), conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            error = "SecurityException: ${e.message}"
        } finally {
            if (bound) runCatching { unbindService(conn) }
        }
        // 系统的异常消息不带权限名（"Not allowed to bind to service …"）；权限名在 ActivityManager 的 logcat 里，由 run.py 核对
        val denied = !bound && error?.startsWith("SecurityException") == true
        return JSONObject().put("ok", visible && denied)
            .put("summary", "visible=$visible bound=$bound denied=$denied")
            .put("visible", visible).put("bound", bound).put("error", error ?: JSONObject.NULL)
    }
}
