package org.agentos.spike.s2.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class ClientActivity : Activity() {
    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        fun button(label: String, intent: () -> Intent) =
            col.addView(Button(this).apply {
                text = label
                setOnClickListener { sendBroadcast(intent().setClass(this@ClientActivity, ProbeReceiver::class.java)) }
            })
        button("Bind + ping") { Intent("org.agentos.spike.s2.client.PROBE") }
        button("Bind + 开始 2 分钟任务（前台发起）") {
            Intent("org.agentos.spike.s2.client.PROBE").putExtra("task_ms", 120_000L)
        }
        button("冒充监督进程") { Intent("org.agentos.spike.s2.client.SPOOF") }
        col.addView(Button(this).apply { text = "刷新"; setOnClickListener { refresh() } })
        out = TextView(this).apply { setTextIsSelectable(true); textSize = 12f }
        col.addView(out)
        setContentView(col)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        out.text = try {
            File(filesDir, "probe.log").readLines().takeLast(20).joinToString("\n")
        } catch (e: Exception) {
            "(no probe results)"
        }
    }
}
