package org.tinitalk.i18n

/** Resource-backed presentation tests do not need account, push or Telecom startup. */
class LocalizedTestApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguage.initialize(this)
    }
}
