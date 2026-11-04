package com.morsen.ts3xclient

import android.app.Application

class Ts3xApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashDumpStore.install(this)
        PokeNotifications.install(this)
        ChatNotifications.install(this)
    }
}
