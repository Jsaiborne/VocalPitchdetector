package com.jsaiborne.vocalpitchdetector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps a recording alive while the screen is off or the app is in the
 * background. Android only lets an app capture the microphone in the background when it runs a
 * foreground service of type "microphone", and the notification is what tells the user that
 * recording is still going on. The recording itself is done by the shared [PitchEngine]; this
 * service only shows the notification, offers Pause/Resume and Stop, and ends itself as soon as
 * the recording is over (however it was stopped).
 */
@Suppress("TooManyFunctions")
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = PitchEngineProvider.get()

        when (intent?.action) {
            ACTION_STOP -> {
                finishRecording(engine)
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PAUSE ->
                if (engine.isPaused.value) engine.resumeRecording() else engine.pauseRecording()
        }

        // A service started with startForegroundService must call startForeground promptly
        if (!enterForeground(buildNotification(engine.isPaused.value)) || !engine.isRecording.value) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        observe(engine)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observeJob?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    /** Follows the engine: keeps Pause/Resume up to date and ends the service when recording ends. */
    private fun observe(engine: PitchEngine) {
        observeJob?.cancel()
        observeJob = scope.launch {
            launch {
                engine.isPaused.collect { paused ->
                    if (engine.isRecording.value) {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(paused))
                    }
                }
            }
            engine.isRecording.collect { recording ->
                if (!recording) {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    /** The notification's Stop button: save the recording, drop the notification, and say so. */
    private fun finishRecording(engine: PitchEngine) {
        val wasRecording = engine.isRecording.value
        observeJob?.cancel()

        // Saves the WAV and the pitch trace, exactly like the in-app Stop button
        engine.stopRecording()
        // With no screen open nothing else needs the microphone, so release it
        if (!PitchEngineProvider.uiAttached) engine.stop()

        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        if (wasRecording) {
            Toast.makeText(applicationContext, "Recording saved", Toast.LENGTH_LONG).show()
        }
    }

    private fun enterForeground(notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: IllegalStateException) {
        // e.g. Android refused to start a foreground service from the background
        Log.e(TAG, "Could not start the recording service in the foreground", e)
        false
    } catch (e: SecurityException) {
        Log.e(TAG, "Missing permission for the recording service", e)
        false
    }

    private fun buildNotification(paused: Boolean): Notification {
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            ),
            immutable
        )
        val togglePause = PendingIntent.getService(
            this,
            REQUEST_TOGGLE_PAUSE,
            Intent(this, RecordingService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            immutable
        )
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            immutable
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(if (paused) "Recording paused" else "Recording")
            .setContentText(
                if (paused) "Tap Resume to keep recording" else "Your vocal session is being recorded"
            )
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (paused) "Resume" else "Pause", togglePause)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while a vocal session is being recorded"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Keeps the CPU running so capture isn't cut off when the screen is off. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VocalPitchDetector:Recording").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_TOGGLE_PAUSE = "com.jsaiborne.vocalpitchdetector.action.TOGGLE_PAUSE"
        private const val ACTION_STOP = "com.jsaiborne.vocalpitchdetector.action.STOP"
        private const val REQUEST_OPEN = 0
        private const val REQUEST_TOGGLE_PAUSE = 1
        private const val REQUEST_STOP = 2
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L

        /** Call right after a recording has started, while the app is visible. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        }
    }
}
