package com.roflochinsky.noteapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground-сервис записи. Истина тумблера живёт здесь; onStartCommand сериализован main-тредом,
 * решения идемпотентны (вердикт LLD-1). Отказы записи логируются и не роняют зонд (вердикт LLD-3).
 */
class RecordingService : Service() {

    private val toggleState = ToggleState()
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startedAtMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE ->
                when (toggleState.toggle()) {
                    ToggleDecision.START -> {
                        Log.i(Probe.LOG_TAG, "PROBE:TOGGLE decision=start")
                        startRecording()
                    }
                    ToggleDecision.STOP -> {
                        Log.i(Probe.LOG_TAG, "PROBE:TOGGLE decision=stop")
                        stopRecording()
                    }
                    ToggleDecision.NOOP -> Log.i(Probe.LOG_TAG, "PROBE:TOGGLE decision=dup")
                }
            ACTION_STOP ->
                when (toggleState.stop()) {
                    ToggleDecision.STOP -> {
                        Log.i(Probe.LOG_TAG, "PROBE:TOGGLE decision=stop")
                        stopRecording()
                    }
                    else -> Log.i(Probe.LOG_TAG, "PROBE:TOGGLE decision=dup")
                }
            else -> Log.i(Probe.LOG_TAG, "PROBE:TOGGLE decision=dup")
        }
        return START_NOT_STICKY
    }

    @Suppress("TooGenericExceptionCaught") // MediaRecorder кидает Runtime/IllegalState — зонд
    // обязан пережить любой отказ и записать причину (вердикт LLD-3).
    private fun startRecording() {
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(Probe.LOG_TAG, "PROBE:FGS_STARTED")
        isRunning = true
        try {
            val dir = File(filesDir, PROBE_DIR).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "$stamp.m4a")
            recorder =
                MediaRecorder(this).apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
            currentFile = file
            startedAtMs = System.currentTimeMillis()
            Log.i(Probe.LOG_TAG, "PROBE:REC_START file=${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(Probe.LOG_TAG, "PROBE:REC_FAIL ${e.javaClass.simpleName}: ${e.message}")
            releaseRecorder()
            stopSelfSafely()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun stopRecording() {
        val maxAmp =
            try {
                recorder?.maxAmplitude ?: 0
            } catch (e: Exception) {
                Log.w(Probe.LOG_TAG, "PROBE:REC_FAIL amplitude: ${e.message}")
                0
            }
        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.e(Probe.LOG_TAG, "PROBE:REC_FAIL stop: ${e.message}")
        }
        releaseRecorder()
        val bytes = currentFile?.length() ?: 0
        val durMs = if (startedAtMs > 0) System.currentTimeMillis() - startedAtMs else 0
        Log.i(Probe.LOG_TAG, "PROBE:REC_STOP bytes=$bytes durMs=$durMs maxAmp=$maxAmp")
        stopSelfSafely()
    }

    private fun releaseRecorder() {
        recorder?.release()
        recorder = null
    }

    private fun stopSelfSafely() {
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Запись", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent =
            PendingIntent.getService(
                this,
                0,
                Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Идёт запись")
            .setUsesChronometer(true)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Стоп", stopIntent).build())
            .build()
    }

    companion object {
        const val ACTION_TOGGLE = "com.roflochinsky.noteapp.TOGGLE"
        const val ACTION_STOP = "com.roflochinsky.noteapp.STOP"
        const val PROBE_DIR = "probe"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        /** Читается экраном статуса; точность best-effort, для зонда достаточно. */
        @Volatile
        var isRunning = false
            private set

        fun toggleIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_TOGGLE)
    }
}
