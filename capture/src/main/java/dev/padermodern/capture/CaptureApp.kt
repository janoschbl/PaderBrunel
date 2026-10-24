package dev.padermodern.capture

import android.app.Application

class CaptureApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CaptureHub.init(this)
    }
}
