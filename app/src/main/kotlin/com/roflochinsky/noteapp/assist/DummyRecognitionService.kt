package com.roflochinsky.noteapp.assist

import android.content.Intent
import android.speech.RecognitionService

/**
 * Заглушка. Атрибут recognitionService обязан указывать на живой компонент — иначе система молча
 * сбрасывает роль ассистента при каждой переустановке APK (research §1.4a).
 */
class DummyRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) = Unit

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
