package com.roflochinsky.noteapp.assist

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.roflochinsky.noteapp.Probe
import com.roflochinsky.noteapp.RecordingService

/**
 * Вариант B: ассистент как ACTION_ASSIST-активити — фолбэк на случай, если OxygenOS блокирует
 * VoiceInteractionService на кнопке питания (research §2.7.1).
 */
class AssistTriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(Probe.LOG_TAG, "PROBE:ASSIST_ACTIVITY action=${intent?.action}")
        startForegroundService(RecordingService.toggleIntent(this))
        finish()
    }
}
