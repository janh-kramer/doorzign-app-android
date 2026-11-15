package de.doorzign.app

import de.doorzign.app.ui.common.Texts

import android.app.Application

class DoorZignApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        de.doorzign.app.ui.common.Texts.init(this)
        container = AppContainer(this)
    }
}
