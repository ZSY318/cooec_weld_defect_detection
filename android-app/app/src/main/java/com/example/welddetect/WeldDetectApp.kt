package com.example.welddetect

import android.app.Application

class WeldDetectApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
