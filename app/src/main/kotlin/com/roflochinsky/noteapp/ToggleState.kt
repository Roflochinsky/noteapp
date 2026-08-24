package com.roflochinsky.noteapp

enum class ToggleDecision {
    START,
    STOP,
    NOOP,
}

/**
 * Чистая логика тумблера записи. Истина живёт в RecordingService, решения идемпотентны (вердикт
 * LLD-1): повторный toggle не даёт двойного START, stop при простое — NOOP.
 */
class ToggleState {
    var recording = false
        private set

    fun toggle(): ToggleDecision =
        if (recording) {
            recording = false
            ToggleDecision.STOP
        } else {
            recording = true
            ToggleDecision.START
        }

    fun stop(): ToggleDecision =
        if (recording) {
            recording = false
            ToggleDecision.STOP
        } else {
            ToggleDecision.NOOP
        }
}
