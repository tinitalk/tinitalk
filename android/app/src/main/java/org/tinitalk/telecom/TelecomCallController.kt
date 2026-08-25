package org.tinitalk.telecom

import android.content.Context
import androidx.core.telecom.CallsManager

enum class TelecomCapabilities {
    AudioOnly,
}

interface TelecomRegistrar {
    fun register(capabilities: TelecomCapabilities)
}

class TelecomCallController(private val registrar: TelecomRegistrar) {
    fun registerAudioOnly() {
        registrar.register(TelecomCapabilities.AudioOnly)
    }
}

class AndroidTelecomRegistrar(context: Context) : TelecomRegistrar {
    private val callsManager = CallsManager(context.applicationContext)

    override fun register(capabilities: TelecomCapabilities) {
        callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
    }
}
