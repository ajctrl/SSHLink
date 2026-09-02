package com.example.sshlink

import android.content.Context
import java.io.File

class SettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val store = ConfigStore(File(appContext.filesDir, "config.json"))
    private val runtimePrefs = appContext.getSharedPreferences("runtime", Context.MODE_PRIVATE)

    @Throws(ConfigStore.ConfigCorruptedException::class)
    fun load(): AppConfig = store.load()

    fun save(config: AppConfig) = store.save(config)

    @Throws(ConfigStore.ConfigCorruptedException::class)
    fun exportJson(): String = store.exportJson()

    fun importJson(text: String): AppConfig = store.importJson(text)

    fun isTunnelDesired(): Boolean = runtimePrefs.getBoolean("tunnelDesired", false)

    fun setTunnelDesired(value: Boolean) {
        // commit() makes the user's Start/Stop intent durable before the service
        // lifecycle can race with a reconnect command or process restart.
        if (!runtimePrefs.edit().putBoolean("tunnelDesired", value).commit()) {
            TunnelState.log("ERROR", "Could not persist tunnel desired state")
        }
    }

    fun isRetryBlocked(): Boolean = runtimePrefs.getBoolean("retryBlocked", false)

    fun retryBlockedReason(): String = runtimePrefs.getString("retryBlockedReason", "") ?: ""

    fun setRetryBlocked(value: Boolean, reason: String = "") {
        val saved = runtimePrefs.edit()
            .putBoolean("retryBlocked", value)
            .putString("retryBlockedReason", if (value) reason else "")
            .commit()
        if (!saved) TunnelState.log("ERROR", "Could not persist retry-blocked state")
    }
}
