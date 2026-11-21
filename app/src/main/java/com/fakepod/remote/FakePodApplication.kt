package com.fakepod.remote

import android.app.Application

class FakePodApplication : Application() {
    lateinit var bleClient: FakePodBleClient
        private set

    override fun onCreate() {
        super.onCreate()
        bleClient = FakePodBleClient(this)
    }
}
