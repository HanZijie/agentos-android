package org.agentos.spike.s3.api

import android.content.ComponentName

object SpikeIds {
    const val SERVER_PKG = "org.agentos.spike.s3.agent"
    const val CLIENT_PKG = "org.agentos.spike.s3.client"
    val ACP_SERVICE = ComponentName(SERVER_PKG, "$SERVER_PKG.AcpService")
    val PROBE_SERVICE = ComponentName(SERVER_PKG, "$SERVER_PKG.ProbeService")
    val BENCH_SERVICE = ComponentName(SERVER_PKG, "$SERVER_PKG.BenchService")
    /** 电脑端网关的抽象 socket 名，与 architecture.md 5.1 一致。 */
    const val GATEWAY_SOCKET = "agentos-acp"
}
