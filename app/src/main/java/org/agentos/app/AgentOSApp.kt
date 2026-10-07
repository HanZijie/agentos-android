package org.agentos.app

import android.app.Application
import android.os.Process
import org.agentos.app.ui.consent.ConsentHost

/**
 * 应用入口。所有进程（主进程、:agent、:ext）都会创建它；只有主进程注册确认界面的宿主（前台 Activity 跟踪）。
 */
class AgentOSApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (isMainProcess()) ConsentHost.install(this)
    }

    private fun isMainProcess(): Boolean {
        val name = if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName() else null
        return name == packageName
    }
}
