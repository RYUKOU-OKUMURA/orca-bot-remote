package dev.okumura.orcaremote

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import java.io.IOException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

// Main-thread only. A queued foreground start must promote itself before it can be stopped.
private var stopPromotedStart: (() -> Unit)? = null

internal fun reconcileNotifications(context: Context) {
    val prefs = context.connectionPreferences()
    val enabled = prefs.enabledConnection() != null
    scheduleNotifications(context, enabled)
    val service = Intent(context, RealtimeNotificationService::class.java)
    if (enabled && prefs.notificationPrefs().realtime) {
        // Background starts can be denied even after a boot/update broadcast.
        runCatching { context.startForegroundService(service) }
    } else stopPromotedStart?.invoke()
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            reconcileNotifications(context)
        }
    }
}

class RealtimeNotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
        CoroutineExceptionHandler { _, failure ->
            android.util.Log.w("OrcaNotifications", "Realtime sync stopped: ${failure.javaClass.simpleName}")
            stopPromotedStart?.invoke()
        })
    private val networkChanged = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    private var connected: Connection? = null
    private lateinit var connectivity: ConnectivityManager
    private var callbackRegistered = false
    private var lastNetwork: Network? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (network != lastNetwork) networkChanged.trySend(Unit)
            lastNetwork = network
        }

        override fun onLost(network: Network) {
            if (network == lastNetwork) lastNetwork = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannels(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val status = Notification.Builder(this, RealtimeChannel)
            .setSmallIcon(R.drawable.orca_notification)
            .setContentTitle("Mac mini の通知を待機中")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        val promoted = runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, status, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            } else startForeground(1, status)
        }
        if (promoted.isFailure) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        stopPromotedStart = { stopSelf(startId) }
        val prefs = connectionPreferences()
        val connection = prefs.enabledConnection()
        if (connection == null || !prefs.notificationPrefs().realtime) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!connection.matches(connected) || loop?.isActive != true) {
            loop?.cancel()
            connected = connection
            if (!callbackRegistered) {
                connectivity = getSystemService(ConnectivityManager::class.java)
                callbackRegistered = runCatching {
                    lastNetwork = connectivity.activeNetwork
                    connectivity.registerDefaultNetworkCallback(callback)
                }.isSuccess
            }
            loop = scope.launch { listen(connection) }
        }
        return START_STICKY
    }

    private enum class End { RETRY, NETWORK_CHANGED, STOP }

    private suspend fun listen(connection: Connection) = coroutineScope {
        val api = OrcaApi(connection)
        var failures = 0
        while (isActive) {
            val call = api.eventsCall()
            val end = coroutineScope {
                val reader = async(Dispatchers.IO) {
                    try {
                        call.execute().use { response ->
                            when {
                                response.code == 401 || response.code == 404 -> End.STOP
                                response.isSuccessful && response.body.contentType()?.subtype == "event-stream" -> {
                                    readEvents(response.body.charStream().buffered()) { event ->
                                        event.syncTarget()?.let { target ->
                                            failures = 0
                                            NotificationSync.sync(this@RealtimeNotificationService, target)
                                        }
                                    }
                                    End.RETRY
                                }
                                else -> End.RETRY
                            }
                        }
                    } catch (_: IOException) { End.RETRY }
                }
                try {
                    select {
                        reader.onAwait { it }
                        networkChanged.onReceive { End.NETWORK_CHANGED }
                    }
                } finally {
                    call.cancel() // Unblock a stalled socket on service stop, settings change or network handover.
                    reader.cancel()
                }
            }
            when (end) {
                End.STOP -> { stopPromotedStart?.invoke(); return@coroutineScope }
                End.NETWORK_CHANGED -> failures = 0
                End.RETRY -> {
                    if (withTimeoutOrNull(reconnectDelay(failures)) { networkChanged.receive() } != null) failures = 0
                    else failures = (failures + 1).coerceAtMost(4)
                }
            }
        }
    }

    override fun onDestroy() {
        stopPromotedStart = null
        scope.cancel()
        if (callbackRegistered) connectivity.unregisterNetworkCallback(callback)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
