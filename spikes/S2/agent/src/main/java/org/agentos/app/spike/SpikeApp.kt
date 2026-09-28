package org.agentos.app.spike

import android.app.Application
import org.agentos.app.agent.Runtime

class SpikeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (getProcessName().endsWith(":agent")) {
            Runtime.onProcessStart(this)
        }
    }
}
