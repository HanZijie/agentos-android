package org.agentos.sample.notes.agentos

import android.content.Context

/** release：只用真网关，没有任何开关。 */
object GatewayProvider {
    fun create(context: Context): AgentOsGateway = createRealGateway(context)
}
