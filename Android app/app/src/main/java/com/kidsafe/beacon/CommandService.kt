package com.kidsafe.beacon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Foreground service that runs on the monitored (kid's) phone. It long-polls the
 * Telegram bot for commands sent by the parent and performs the matching action.
 * Everything goes through Telegram's free API — no third-party server.
 */
class CommandService : LifecycleService() {

    private var pollJob: Job? = null
    private var lastUpdateId = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundSafely()
        if (pollJob == null) pollJob = lifecycleScope.launch { pollLoop() }
        return START_STICKY
    }

    private fun startForegroundSafely() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KidSafe Beacon active")
            .setContentText("Listening for safety commands")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fullType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            try {
                // Preferred: declare all types so camera/mic commands work.
                startForeground(NOTIF_ID, notification, fullType)
            } catch (e: Exception) {
                // Android 14 blocks camera/mic types when launched from boot/background.
                // Fall back to location-only so the listener still runs; escalate later.
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            }
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /**
     * Best-effort upgrade to camera+microphone foreground type right before a
     * capture, in case the service started from boot with location-only. Safe to
     * call repeatedly; failures are ignored and the capture is still attempted.
     */
    private fun tryEscalateTypes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KidSafe Beacon active")
            .setContentText("Listening for safety commands")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val fullType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(NOTIF_ID, notification, fullType)
        } catch (_: Exception) {
        }
    }

    private suspend fun pollLoop() {
        val prefs = Prefs(this)
        if (!prefs.isConfigured()) {
            stopSelf()
            return
        }
        val tg = TelegramClient(prefs.token, prefs.chatId)
        val allowedChatId = prefs.chatId

        tg.sendMessage("KidSafe Beacon is now listening. Send /help for commands.")

        while (lifecycleScope.isActive) {
            val json = tg.getUpdates(lastUpdateId + 1)
            if (json == null) {
                delay(3000)
                continue
            }
            try {
                val result = JSONObject(json).optJSONArray("result") ?: continue
                for (i in 0 until result.length()) {
                    val update = result.getJSONObject(i)
                    lastUpdateId = update.getLong("update_id")
                    val message = update.optJSONObject("message") ?: continue
                    val chatId = message.getJSONObject("chat").getLong("id").toString()
                    val text = message.optString("text").trim()
                    // Only obey the configured parent chat.
                    if (chatId != allowedChatId) continue
                    handleCommand(tg, text)
                }
            } catch (e: Exception) {
                delay(2000)
            }
        }
    }

    private suspend fun handleCommand(tg: TelegramClient, rawText: String) {
        val cmd = rawText.substringBefore('@').lowercase()
        when {
            cmd.startsWith("/location") -> {
                tg.sendMessage("Getting location…")
                val loc = LocationHelper(this).currentLocation()
                if (loc != null) tg.sendLocation(loc.latitude, loc.longitude)
                else tg.sendMessage("Could not get location (is GPS on?).")
            }

            cmd.startsWith("/photo_front") -> capturePhoto(tg, front = true)
            cmd.startsWith("/photo") -> capturePhoto(tg, front = false)

            cmd.startsWith("/audio") -> {
                tryEscalateTypes()
                tg.sendMessage("Recording 10s of audio…")
                val file = AudioRecorder(this).record(10_000)
                if (file != null) {
                    tg.sendAudio(file, "Audio update")
                    file.delete()
                } else tg.sendMessage("Could not record audio.")
            }

            cmd.startsWith("/stop") -> {
                Prefs(this).monitoringEnabled = false
                tg.sendMessage("Stopping. The app will no longer respond until restarted on the phone.")
                stopSelf()
            }

            cmd.startsWith("/help") || cmd.startsWith("/start") -> {
                tg.sendMessage(
                    "Commands:\n" +
                        "/location – send current location\n" +
                        "/photo – rear camera photo\n" +
                        "/photo_front – front camera photo\n" +
                        "/audio – record & send 10s audio\n" +
                        "/stop – stop listening"
                )
            }
        }
    }

    private suspend fun capturePhoto(tg: TelegramClient, front: Boolean) {
        tryEscalateTypes()
        tg.sendMessage(if (front) "Taking front photo…" else "Taking photo…")
        val file = PhotoCapture(this, this).capture(useFrontCamera = front)
        if (file != null) {
            tg.sendPhoto(file, "Photo update")
            file.delete()
        } else tg.sendMessage("Could not take photo.")
    }

    override fun onDestroy() {
        pollJob?.cancel()
        pollJob = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "KidSafe Beacon",
                NotificationManager.IMPORTANCE_LOW
            )
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "kidsafe_beacon"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, CommandService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CommandService::class.java))
        }
    }
}
