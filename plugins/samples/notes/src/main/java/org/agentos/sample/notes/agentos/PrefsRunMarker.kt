package org.agentos.sample.notes.agentos

import android.content.SharedPreferences

/** 把“上一轮还没走完”的标记放在 SharedPreferences 里：进程被杀后下次启动还在。同步写（commit），免得进程恰好此时被杀。 */
class PrefsRunMarker(private val prefs: SharedPreferences) : RunMarker {
    override fun set() {
        prefs.edit().putBoolean(KEY, true).commit()
    }

    override fun clear() {
        if (prefs.getBoolean(KEY, false)) prefs.edit().remove(KEY).commit()
    }

    override fun isSet(): Boolean = prefs.getBoolean(KEY, false)

    private companion object {
        const val KEY = "agentos_run_in_progress"
    }
}
