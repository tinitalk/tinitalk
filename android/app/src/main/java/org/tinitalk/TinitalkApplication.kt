package org.tinitalk

import android.app.Application
import org.tinitalk.telecom.AndroidTelecomRegistrar
import org.tinitalk.telecom.TelecomCallController

class TinitalkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { TelecomCallController(AndroidTelecomRegistrar(this)).registerAudioOnly() }
    }
}
