package com.jsaiborne.vocalpitchdetector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps playback going while the screen is off or the app is in the
 * background, and shows a notification with Play/Pause and Stop. The player itself lives in the
 * playback screen's [PlaybackViewModel]; this service follows [PlaybackSession.status] and ends
 * itself as soon as no recording is open for playback any more.
 */
@Suppress("TooManyFunctions")
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var noisyReceiverRegistered = false

    // Unplugging headphones would otherwise switch playback to the loudspeaker
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                PlaybackSession.controls?.pause()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PLAY -> PlaybackSession.controls?.togglePlayPause()
            ACTION_STOP -> PlaybackSession.controls?.stopPlayback()
        }

        val status = PlaybackSession.status.value
        // A service started with startForegroundService must call startForeground promptly
        if (!enterForeground(buildNotification(status)) || status == null) {
            finish()
            return START_NOT_STICKY
        }

        registerNoisyReceiver()
        observe()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The app was swiped away from recents: stop playing rather than play on with no screen
        PlaybackSession.controls?.stopPlayback()
        finish()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        observeJob?.cancel()
        unregisterNoisyReceiver()
        scope.cancel()
        super.onDestroy()
    }

    /** Keeps the notification in step with playback and ends the service once playback is closed. */
    private fun observe() {
        if (observeJob?.isActive == true) return
        observeJob = scope.launch {
            PlaybackSession.status.collect { status ->
                if (status == null) {
                    finish()
                } else {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(status))
                }
            }
        }
    }

    private fun finish() {
        observeJob?.cancel()
        unregisterNoisyReceiver()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun enterForeground(notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: IllegalStateException) {
        // e.g. Android refused to start a foreground service from the background
        Log.e(TAG, "Could not start the playback service in the foreground", e)
        false
    } catch (e: SecurityException) {
        Log.e(TAG, "Missing permission for the playback service", e)
        false
    }

    private fun buildNotification(status: PlaybackStatus?): Notification {
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val playing = status?.isPlaying == true

        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            ),
            immutable
        )
        val togglePlay = PendingIntent.getService(
            this,
            REQUEST_TOGGLE_PLAY,
            Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE_PLAY),
            immutable
        )
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            immutable
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_play)
            .setContentTitle(status?.title?.takeIf { it.isNotBlank() } ?: "Vocal recording")
            .setContentText(if (playing) "Playing" else "Paused")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (playing) "Pause" else "Play", togglePlay)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while a recording is playing"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyReceiverRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        unregisterReceiver(noisyReceiver)
        noisyReceiverRegistered = false
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_TOGGLE_PLAY = "com.jsaiborne.vocalpitchdetector.action.TOGGLE_PLAY"
        private const val ACTION_STOP = "com.jsaiborne.vocalpitchdetector.action.STOP_PLAYBACK"
        private const val REQUEST_OPEN = 10
        private const val REQUEST_TOGGLE_PLAY = 11
        private const val REQUEST_STOP = 12

        @Volatile
        private var isRunning = false

        /** Call when playback starts, while the app is visible. Does nothing if already running. */
        fun start(context: Context) {
            if (isRunning) return
            ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
        }
    }
}
