package com.khoichon.rush

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.random.Random

/**
 * The always-on part: a foreground service that owns the overlay windows and the encounter engine,
 * and runs the spawn timer. Rush only ever shows up while this runs.
 */
class RushService : Service() {
    companion object {
        const val ACTION_START = "com.khoichon.rush.START"
        const val ACTION_STOP = "com.khoichon.rush.STOP"
        const val ACTION_SPAWN = "com.khoichon.rush.SPAWN"
        const val ACTION_JUMPSCARE = "com.khoichon.rush.JUMPSCARE"
        const val ACTION_CONFIG_CHANGED = "com.khoichon.rush.CONFIG_CHANGED"

        private const val CHANNEL_ID = "rush"
        private const val NOTIF_ID = 1

        @Volatile
        var running = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var cfg: Config
    private lateinit var overlay: OverlayController
    private lateinit var engine: RushEngine

    private val spawnTick = object : Runnable {
        override fun run() {
            if (!cfg.spawnAutomatically) return
            if (canSpawnNow()) {
                if (!engine.active) engine.trigger(allowFake = true)
                scheduleNext()
            } else {
                handler.postDelayed(this, 3000) // not while the screen is off / locked
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        cfg = Config(this)
        overlay = OverlayController(this, RushState())
        engine = RushEngine(this, cfg, overlay, overlay.state)
        engine.onCaught = { runDeathConsequences() }
        createChannel()
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), foregroundType())
        scheduleNext()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SPAWN -> if (Settings.canDrawOverlays(this)) {
                scheduleNext() // a manual call restarts the timer
                engine.trigger(allowFake = false)
            }
            ACTION_JUMPSCARE -> engine.jumpscareNow()
            ACTION_CONFIG_CHANGED -> scheduleNext()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay.relayout()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        engine.release()
        overlay.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- spawn timer

    private fun scheduleNext() {
        handler.removeCallbacks(spawnTick)
        if (!cfg.spawnAutomatically) return
        val (lo, hi) = cfg.spawnRange()
        val delaySec = lo + Random.nextInt(hi - lo + 1)
        handler.postDelayed(spawnTick, delaySec * 1000L)
    }

    private fun canSpawnNow(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return pm.isInteractive && !km.isKeyguardLocked
    }

    // ---------------------------------------------------------------- death consequences

    private fun runDeathConsequences() {
        if (cfg.testMode) return // test mode never applies them
        when (cfg.deathAction) {
            DeathAction.NONE -> Unit
            DeathAction.HOME -> runCatching {
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            DeathAction.GUIDING_LIGHT -> runCatching {
                // allowed from the background because we hold "display over other apps"
                if (GuidingLight.isInstalled(this)) startActivity(GuidingLight.intent())
            }
            DeathAction.LOCK -> runCatching {
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (dpm.isAdminActive(ComponentName(this, RushAdmin::class.java))) dpm.lockNow()
            }
        }
    }

    // ---------------------------------------------------------------- notification

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "RUSH", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while RUSH is lurking"
                setShowBadge(false)
            }
        )
    }

    private fun servicePi(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, RushService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rush)
            .setContentTitle("RUSH is lurking")
            .setContentText("Stop it here if it ever gets too much.")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Call Rush", servicePi(ACTION_SPAWN, 1))
            .addAction(0, "Stop", servicePi(ACTION_STOP, 2))
            .build()
    }
}
