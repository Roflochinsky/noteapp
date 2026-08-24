package com.roflochinsky.noteapp.assist

import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.roflochinsky.noteapp.Probe

/** Вариант A: держатель роли ассистента. Логики нет — вся работа в сессии. */
class AssistService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // Не собирать assist-контекст: без этого система гоняет скриншот и disclosure-анимацию
        // на каждое нажатие (research §6).
        setDisabledShowContext(
            VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
        )
        Log.i(Probe.LOG_TAG, "PROBE:ASSIST_SERVICE_READY")
    }
}
