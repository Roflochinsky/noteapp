package com.roflochinsky.noteapp.pipeline

import android.content.Context

/**
 * Секреты в SharedPreferences MODE_PRIVATE: для личного устройства достаточно (вердикт LLD-1 плана
 * v1; фиксируется ADR приватности в срезе С2).
 */
object Settings {
    private const val PREFS = "settings"
    private const val KEY_DEEPGRAM = "deepgram_key"
    private const val KEY_ELEVENLABS = "elevenlabs_key"
    private const val KEY_GH_TOKEN = "github_token"
    private const val KEY_GH_REPO = "github_repo"
    private const val DEFAULT_REPO = "Roflochinsky/voice-notes"

    /**
     * Ключ боевого распознавания (ADR `2026-09-06-stt-elevenlabs-scribe-v2`). Ключ Deepgram лежит
     * рядом отдельным полем и не стирается: он путь отката того же ADR.
     */
    fun elevenLabsKey(context: Context): String? =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ELEVENLABS, null)
            ?.takeIf { it.isNotBlank() }

    fun setElevenLabsKey(context: Context, value: String) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ELEVENLABS, value.trim())
            .apply()
    }

    fun deepgramKey(context: Context): String? =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DEEPGRAM, null)
            ?.takeIf { it.isNotBlank() }

    fun githubToken(context: Context): String? =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GH_TOKEN, null)
            ?.takeIf { it.isNotBlank() }

    fun setGithubToken(context: Context, value: String) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GH_TOKEN, value.trim())
            .apply()
    }

    fun githubRepo(context: Context): String =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GH_REPO, null)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_REPO

    fun setDeepgramKey(context: Context, value: String) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEEPGRAM, value.trim())
            .apply()
    }
}
