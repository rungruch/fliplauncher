package com.fliplauncher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Owns provider callbacks while Samsung renders the native list outside our activity. */
class NativeWidgetRelayService : Service() {
    private lateinit var host: RelayHost
    private lateinit var nativeContext: Context
    private lateinit var environment: WidgetDisplayEnvironment
    private lateinit var displayMonitor: WidgetDisplayMonitor
    private var listening = false
    private var ids = emptyList<Int>()
    @Volatile private var foreground = false

    private inner class RelayHost(context: Context) : AppWidgetHost(context, WidgetScreenStore.HOST_ID) {
        override fun onCreateView(context: Context, id: Int, info: AppWidgetProviderInfo): AppWidgetHostView =
            object : AppWidgetHostView(context) {
                override fun updateAppWidget(views: RemoteViews?) {
                    // Capture without inflating a second live widget or binding its nested adapters.
                    runCatching { NativeWidgetRelay.capture(context, id, appWidgetInfo ?: info, views) }
                        .onFailure { Log.w(TAG, "Cannot capture widget $id", it) }
                }
            }

        override fun onProviderChanged(id: Int, info: AppWidgetProviderInfo) {
            NativeWidgetRelay.forget(this@NativeWidgetRelayService, id)
            super.onProviderChanged(id, info)
        }

        override fun onAppWidgetRemoved(id: Int) {
            super.onAppWidgetRemoved(id)
            WidgetScreenStore.forget(this@NativeWidgetRelayService, id)
            NativeWidgetRelay.forget(this@NativeWidgetRelayService, id)
            syncBindings()
            FlipLauncherWidgetProvider.refresh(this@NativeWidgetRelayService)
        }

        fun releaseViews() = clearViews()
    }

    override fun onCreate() {
        super.onCreate()
        nativeContext = NativeWidgetSizing.context(this)
        environment = WidgetDisplayEnvironment.read(nativeContext)
        host = RelayHost(nativeContext)
        displayMonitor = WidgetDisplayMonitor(this, Handler(Looper.getMainLooper())) {
            if (foreground) runCatching {
                syncBindings()
                FlipLauncherWidgetProvider.refresh(this)
            }.onFailure { Log.w(TAG, "Cannot refresh display subscriptions", it) }
        }
        NativeRelayRuntime.attach(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        displayMonitor.configurationChanged()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopUpdates(pause = true)
            return START_NOT_STICKY
        }
        // Sticky restarts and automatic starts honour the user's pause; editor starts clear it.
        if ((intent?.action != ACTION_START && paused(this)) ||
            FlipLauncherWidgetProvider.widgetIds(this).isEmpty() || WidgetScreenStore.ids(this).isEmpty()) {
            stopUpdates()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) prefs(this).edit().putBoolean(PAUSED, false).apply()
        try {
            showNotification()
            foreground = true
            syncBindings()
            FlipLauncherWidgetProvider.refresh(this)
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Native widget updates could not start", failure)
            stopUpdates()
        }
        CoverSetupStatusStore.surfaceChanged(this)
        return if (foreground) START_STICKY else START_NOT_STICKY
    }

    fun syncBindings() {
        if (!foreground) return
        val outerIds = FlipLauncherWidgetProvider.widgetIds(this)
        val saved = WidgetScreenStore.ids(this)
        if (outerIds.isEmpty() || saved.isEmpty()) { stopUpdates(); return }
        val nextContext = NativeWidgetSizing.context(this)
        val nextEnvironment = WidgetDisplayEnvironment.read(nextContext)
        if (nextEnvironment != environment) {
            // Stop only our callback; a paused service must not stop the dashboard's host.
            pauseListening()
            host.releaseViews()
            nativeContext = nextContext
            environment = nextEnvironment
            host = RelayHost(nativeContext)
            ids = emptyList()
            NativeWidgetRelay.reclassify(nativeContext)
        }
        val manager = AppWidgetManager.getInstance(nativeContext)
        val owned = runCatching { host.appWidgetIds.toSet() }.getOrElse { return }
        val valid = saved.mapNotNull { id ->
            if (id !in owned) null else manager.getAppWidgetInfo(id)?.let { id to it }
        }
        val nextIds = valid.map { it.first }
        NativeWidgetRelay.retain(saved.toSet())
        if (nextIds != ids) {
            pauseListening()
            host.releaseViews()
            ids = nextIds
        }
        if (NativeRelayRuntime.coverActive) return
        // Rebuild listeners after handing host 1024 back from the dashboard.
        if (!listening) {
            host.releaseViews()
            valid.forEach { (id, info) ->
                runCatching { host.createView(nativeContext, id, info) }
                    .onFailure { Log.w(TAG, "Cannot subscribe to ${info.provider}", it) }
            }
        }
        val surface = NativeWidgetSizing.surface(this)
        valid.forEach { (id, info) ->
            runCatching { NativeWidgetSizing.update(nativeContext, id, info, surface) }
                .onFailure { Log.w(TAG, "Cannot resize native widget $id", it) }
        }
        if (!listening) runCatching { host.startListening(); listening = true }
            .onFailure {
                Log.w(TAG, "Cannot listen for native widgets", it)
                stopUpdates()
            }
    }

    fun pauseListening() {
        // Never stop the dashboard's callback when this service is already paused.
        if (listening) {
            runCatching { host.stopListening() }
            listening = false
        }
    }

    fun stopUpdates(pause: Boolean = false) {
        if (pause) prefs(this).edit().putBoolean(PAUSED, true).apply()
        pauseListening()
        foreground = false
        CoverSetupStatusStore.surfaceChanged(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        FlipLauncherWidgetProvider.refresh(this)
    }

    private fun showNotification() {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.native_update_channel), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 9104,
            Intent(this, NativeWidgetRelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val edit = PendingIntent.getActivity(this, 9105,
            Intent(this, WidgetDashboardEditorActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_widget_mark)
            .setContentTitle(getString(R.string.native_update_title))
            .setContentText(getString(R.string.native_update_summary))
            .setContentIntent(edit).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.native_stop_updates), stop).build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    override fun onDestroy() {
        displayMonitor.close()
        pauseListening()
        host.releaseViews()
        NativeRelayRuntime.detach(this)
        CoverSetupStatusStore.surfaceChanged(this)
        FlipLauncherWidgetProvider.refresh(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.fliplauncher.NATIVE_UPDATES_START"
        const val ACTION_STOP = "com.fliplauncher.NATIVE_UPDATES_STOP"
        private const val ACTION_AUTO_START = "com.fliplauncher.NATIVE_UPDATES_AUTO_START"
        private const val CHANNEL = "native_widget_updates"
        private const val NOTIFICATION_ID = 9103
        private const val TAG = "FlipLauncherNative"
        private const val PAUSED = "paused"
        private const val LAST_AUTO_START = "last_auto_start"
        internal const val AUTO_START_INTERVAL_MS = 60_000L
        private fun prefs(context: Context) = context.getSharedPreferences("native_widget_relay", Context.MODE_PRIVATE)
        /** Updates run whenever the cover widget has widgets, unless the user stopped them. */
        fun paused(context: Context) = prefs(context).getBoolean(PAUSED, false)
        fun running() = NativeRelayRuntime.service?.foreground == true

        /**
         * Starts updates without user action when allowed. Background starts need the battery
         * exemption; a blocked or failing start is retried at most once a minute, so stopUpdates
         * refreshing the widget cannot loop back here.
         */
        fun autoStart(context: Context, now: Long = System.currentTimeMillis()): Boolean {
            if (paused(context) || running() || FlipLauncherWidgetProvider.widgetIds(context).isEmpty() ||
                WidgetScreenStore.ids(context).isEmpty()) return false
            val prefs = prefs(context)
            val last = prefs.getLong(LAST_AUTO_START, 0L)
            if (now in last until last + AUTO_START_INTERVAL_MS) return false
            prefs.edit().putLong(LAST_AUTO_START, now).apply()
            return try {
                ContextCompat.startForegroundService(context,
                    Intent(context, NativeWidgetRelayService::class.java).setAction(ACTION_AUTO_START))
                true
            } catch (failure: RuntimeException) {
                Log.i(TAG, "Native updates cannot start in the background yet", failure)
                false
            }
        }

        fun startFromEditor(context: Context): Boolean {
            if (FlipLauncherWidgetProvider.widgetIds(context).isEmpty()) {
                Toast.makeText(context, R.string.native_enable_first, Toast.LENGTH_LONG).show()
                return false
            }
            if (WidgetScreenStore.ids(context).isEmpty()) return false
            return try {
                ContextCompat.startForegroundService(context,
                    Intent(context, NativeWidgetRelayService::class.java).setAction(ACTION_START))
                true
            } catch (failure: RuntimeException) {
                Log.w(TAG, "Cannot start native updates from editor", failure)
                Toast.makeText(context, R.string.native_start_failed, Toast.LENGTH_LONG).show()
                false
            }
        }

        fun stop(context: Context) {
            prefs(context).edit().putBoolean(PAUSED, true).apply()
            NativeRelayRuntime.service?.stopUpdates()
            FlipLauncherWidgetProvider.refresh(context)
        }
    }
}
