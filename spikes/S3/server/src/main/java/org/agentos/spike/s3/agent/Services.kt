package org.agentos.spike.s3.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.Process
import org.agentos.channel.ChannelConfig
import org.agentos.channel.IAcpService
import org.agentos.channel.IChannel
import org.agentos.spike.s3.api.BenchSender
import org.agentos.spike.s3.api.BenchSinkImpl
import org.agentos.spike.s3.api.IBench
import org.agentos.spike.s3.api.IBenchSink
import org.agentos.spike.s3.api.ISpikeProbe
import org.json.JSONObject

/** 模拟 AgentOS 导出的 ACP 服务。spike 不做 UID 准入（W6 做），只把调用方 UID 绑定到通道上。 */
class AcpService : Service() {
    private val binder = object : IAcpService.Stub() {
        override fun open(client: IChannel?): IChannel {
            val uid = Binder.getCallingUid()
            requireNotNull(client) { "client channel is null" }
            return AgentHost.openBinder(client, uid)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

class ProbeService : Service() {
    private val binder = object : ISpikeProbe.Stub() {
        override fun stats(): String = AgentHost.stats().toString()
        override fun setChannelConfig(json: String?) {
            AgentHost.channelConfig =
                if (json.isNullOrBlank()) ChannelConfig() else ChannelConfig.fromJson(JSONObject(json))
        }
        override fun resetStats() = AgentHost.reset()
        override fun killProcess() = Process.killProcess(Process.myPid())
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

class BenchService : Service() {
    @Volatile private var sink: BenchSinkImpl? = null

    private val binder = object : IBench.Stub() {
        override fun newSink(handlerDelayMicros: Int): IBenchSink = BenchSinkImpl(handlerDelayMicros).also { sink = it }
        override fun sinkStats(): String = sink?.stats()?.toString() ?: "{}"
        override fun blast(target: IBenchSink?, configJson: String?): String =
            BenchSender.blast(requireNotNull(target), JSONObject(configJson ?: "{}")).toString()
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

/** 电脑端网关：`adb shell am start-foreground-service -n org.agentos.spike.s3.agent/.GatewayService` */
class GatewayService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("gateway", "ACP gateway", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "gateway")
            .setContentTitle("S3 ACP gateway")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        AgentHost.startGateway()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
