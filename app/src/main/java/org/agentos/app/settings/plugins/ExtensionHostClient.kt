package org.agentos.app.settings.plugins

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.agentos.app.ext.ExtensionHostService
import org.agentos.internal.IExtensionHost

/**
 * 主进程 → `:ext` 的 IExtensionHost（插件管理页，D5.3；与运行时用的是同一个接口）。绑定一批调用后解除；绑定会拉起 `:ext`。
 * 调用跑在 Dispatchers.IO（它们读写文件、bind 别的 App，不能在主线程）。出错抛 IllegalArgumentException / IllegalStateException，
 * message 形如 `agentos.ext.<code>: …`（[Plugins.errorCode]）。
 */
class ExtensionHostClient(private val context: Context) {
    suspend fun <T> use(timeoutMs: Long = 15_000, block: (IExtensionHost) -> T): T {
        val connected = CompletableDeferred<IBinder?>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                connected.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
            override fun onNullBinding(name: ComponentName?) {
                connected.complete(null)
            }
        }
        check(context.bindService(Intent(context, ExtensionHostService::class.java), conn, Context.BIND_AUTO_CREATE)) { "cannot bind ExtensionHostService" }
        try {
            val binder = withTimeoutOrNull(timeoutMs) { connected.await() } ?: error("ExtensionHostService not connected")
            val host = IExtensionHost.Stub.asInterface(binder)
            return withContext(Dispatchers.IO) { block(host) }
        } finally {
            runCatching { context.unbindService(conn) }
        }
    }
}
