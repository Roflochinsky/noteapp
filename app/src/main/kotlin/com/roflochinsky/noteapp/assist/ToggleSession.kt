package com.roflochinsky.noteapp.assist

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.roflochinsky.noteapp.Probe
import com.roflochinsky.noteapp.RecordingService

/**
 * Сессия-тумблер. UI выключен; каждое нажатие кнопки питания приходит новым onShow —
 * документированный контракт платформы (research §3.1).
 */
class ToggleSession(context: Context) : VoiceInteractionSession(context) {

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val invocationType = args?.getInt(KEY_INVOCATION_TYPE, -1) ?: -1
        val sessionId = args?.getInt(KEY_SHOW_SESSION_ID, -1) ?: -1
        // KEY_SHOW_SESSION_ID — платформенная константа (API 29+), не угаданная строка.
        val screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive ?: false
        val keyguard =
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false
        Log.i(
            Probe.LOG_TAG,
            "PROBE:ONSHOW invocation_type=$invocationType session=$sessionId " +
                "screenOn=$screenOn keyguard=$keyguard",
        )
        // Строго синхронно, пока держится системный биндинг с привилегией фонового
        // mic-FGS; hide() только после (вердикт LLD-2, research §4.3).
        context.startForegroundService(RecordingService.toggleIntent(context))
        hide()
    }

    override fun onLockscreenShown() {
        // Пусто намеренно: дефолт зовёт hide() и убил бы сессию при появлении локскрина
        // (research §4.2).
    }

    private companion object {
        const val KEY_INVOCATION_TYPE = "invocation_type"
        const val KEY_SHOW_SESSION_ID = VoiceInteractionSession.KEY_SHOW_SESSION_ID
    }
}
