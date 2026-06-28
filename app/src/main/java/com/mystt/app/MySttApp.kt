package com.mystt.app

import android.app.Application
import android.content.Context

class MySttApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }
    companion object {
        lateinit var appContext: Context
            private set
    }
}
