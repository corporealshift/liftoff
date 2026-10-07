package com.liftoff.app

import android.app.Application

class LiftoffApplication : Application() {
    lateinit var container: AppContainer private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
