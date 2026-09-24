package com.kidsafe.beacon

import android.content.Context

/** Simple persistence for the Telegram bot token and chat id. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("kidsafe", Context.MODE_PRIVATE)

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(value) = sp.edit().putString("token", value.trim()).apply()

    var chatId: String
        get() = sp.getString("chat_id", "") ?: ""
        set(value) = sp.edit().putString("chat_id", value.trim()).apply()

    /** True while the parent wants the phone to keep listening (survives reboot/update). */
    var monitoringEnabled: Boolean
        get() = sp.getBoolean("monitoring", false)
        set(value) = sp.edit().putBoolean("monitoring", value).apply()

    fun isConfigured(): Boolean = token.isNotBlank() && chatId.isNotBlank()
}
