package org.agentos.sample.notes.agentos

import android.content.Context

/** 真网关的入口：release 和 debug（没打开假网关开关时）都从这里拿。 */
fun createRealGateway(context: Context): AgentOsGateway = RealAgentOsGateway(context)
