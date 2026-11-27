package com.zhaoolee.notes

import android.app.Application
import com.zhaoolee.notes.data.NotesDatabase
import com.zhaoolee.notes.work.NutstoreSyncScheduler

class NotesApplication : Application() {
    val database: NotesDatabase by lazy { NotesDatabase.getInstance(this) }
    override fun onCreate() {
        super.onCreate()
        instance = this
        runCatching { NutstoreSyncScheduler.ensure(this) }
    }
    companion object {
        lateinit var instance: NotesApplication
            private set
    }
}
