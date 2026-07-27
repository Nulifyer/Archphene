package org.archphene.app

import android.app.Application

open class ArchpheneApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ArchphenePreferences.start(this)
    }
}
