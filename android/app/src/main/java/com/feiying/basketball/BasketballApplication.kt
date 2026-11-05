package com.feiying.basketball

import android.app.Application
import com.feiying.basketball.data.AppContainer

class BasketballApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
