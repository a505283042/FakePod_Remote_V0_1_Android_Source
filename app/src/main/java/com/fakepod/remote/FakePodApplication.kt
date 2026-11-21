package com.fakepod.remote

import android.app.Application
import com.fakepod.remote.cover.FakePodCoverHub

class FakePodApplication : Application() {
    lateinit var bleClient: FakePodBleClient
        private set

    override fun onCreate() {
        super.onCreate()
        FakePodCoverHub.initialize(this)
        bleClient = FakePodBleClient(this)
    }
}
