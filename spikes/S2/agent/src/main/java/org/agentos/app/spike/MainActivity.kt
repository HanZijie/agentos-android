package org.agentos.app.spike

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.agentos.app.agent.AgentService
import org.agentos.app.agent.Heartbeat
import org.agentos.app.agent.supervisor.SupervisorStatusReceiver

/** Manual controls for the S2 cases; the automated driver is spikes/S2/s2.sh. */
class MainActivity : Activity() {
    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        fun button(label: String, action: () -> Unit) =
            col.addView(Button(this).apply { text = label; setOnClickListener { action(); refresh() } })

        button("开始任务（2 分钟）") { startTask(120_000L) }
        button("开始长任务（直到停止，100 MB）") { startTask(0L, 100) }
        button("停止所有任务") { send(AgentService.ACTION_STOP_TASKS) }
        button("请求通知权限") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
        button("请求忽略电池优化") {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
        button("刷新") {}
        out = TextView(this).apply { setTextIsSelectable(true); textSize = 12f }
        col.addView(out)
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun startTask(durationMs: Long, ballastMb: Int = 0) =
        startForegroundService(
            Intent(this, AgentService::class.java).setAction(AgentService.ACTION_START_TASK)
                .putExtra(AgentService.EXTRA_DURATION_MS, durationMs)
                .putExtra(AgentService.EXTRA_BALLAST_MB, ballastMb)
        )

    private fun send(action: String) =
        startForegroundService(Intent(this, AgentService::class.java).setAction(action))

    private fun refresh() {
        val pm = getSystemService(PowerManager::class.java)
        val status = try {
            SupervisorStatusReceiver.statusFile(this).readText()
        } catch (e: Exception) {
            "(none)"
        }
        out.text = buildString {
            append("battery exempt: ").append(pm.isIgnoringBatteryOptimizations(packageName)).append("\n\n")
            append("== heartbeat ==\n").append(Heartbeat.read(this@MainActivity)).append('\n')
            append("== supervisor status ==\n").append(status).append('\n')
            append("== events ==\n").append(SpikeLog.tail(this@MainActivity, 40))
        }
    }
}
