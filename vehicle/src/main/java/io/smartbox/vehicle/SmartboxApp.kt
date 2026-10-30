package io.smartbox.vehicle

import android.app.Application
import android.content.Context

class SmartboxApp : Application() {
    lateinit var controller: Controller
        private set

    override fun onCreate() {
        super.onCreate()
        controller = Controller(this).also { it.start() }
    }
}

val Context.controller: Controller get() = (applicationContext as SmartboxApp).controller
