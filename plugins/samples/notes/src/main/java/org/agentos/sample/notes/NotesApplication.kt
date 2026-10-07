package org.agentos.sample.notes

import android.app.Application

class NotesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotesGraph.init(this)
    }
}
