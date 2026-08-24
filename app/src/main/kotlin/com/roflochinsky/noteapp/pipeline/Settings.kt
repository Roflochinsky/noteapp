package com.roflochinsky.noteapp.pipeline

import android.content.Context

/**
 * Секреты в SharedPreferences MODE_PRIVATE: для личного устройства достаточно (вердикт LLD-1 плана
 * v1; фиксируется ADR приватности в срезе С2).
 */
object Settings {
    private const val PREFS = "settings"
    private const val KEY_DEEPGRAM = "deepgram_key"

    fun deepgramKey(context: Context): String? =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DEEPGRAM, null)
            ?.takeIf { it.isNotBlank() }

    fun setDeepgramKey(context: Context, value: String) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEEPGRAM, value.trim())
            .apply()
    }
}
