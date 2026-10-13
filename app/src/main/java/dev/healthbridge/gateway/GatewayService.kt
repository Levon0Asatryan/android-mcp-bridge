package dev.healthbridge.gateway

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.healthbridge.HealthBridgeApplication
import dev.healthbridge.MainActivity
import dev.healthbridge.R
import dev.healthbridge.relay.RelayState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * Manually started foreground service. When the phone is paired it keeps the outbound connection to the private
 * instance.
 */
class GatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as HealthBridgeApplication
    private var mode: String? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("gateway", "Health bridge connection", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            scope.launch { stopAll(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            return START_NOT_STICKY
        }
        val notification = notification("Starting")
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, notification)
        if (mode != null) {
            if (mode == "relay" && app.relay.state.value is RelayState.Failed) app.relay.start()
            return START_NOT_STICKY
        }
        scope.launch {
            val settings = app.settings.settings.first()
            if (!settings.paired) {
                update("Not paired. Pair with your instance in Settings.")
                stopForeground(STOP_FOREGROUND_DETACH); stopSelf(); return@launch
            }
            mode = "relay"
            app.relay.start()
            app.relay.state.collect { state -> update(describe(state)) }
        }
        return START_NOT_STICKY
    }
    private fun describe(state: RelayState): String = when (state) {
        is RelayState.Connected -> "Connected to your instance. Health access follows phone lock and grants."
        is RelayState.Connecting -> "Connecting to your instance"
        is RelayState.Waiting -> "Disconnected: ${state.reason}. Retrying automatically."
        is RelayState.Paused -> "Paused: ${state.reason}"
        is RelayState.Failed -> "Stopped: ${state.reason}"
        RelayState.NotPaired -> "Not paired"
        RelayState.Stopped -> "Stopped"
    }
    private fun update(message: String) = getSystemService(NotificationManager::class.java).notify(1, notification(message))
    private suspend fun stopAll() {
        app.relay.stop()
        mode = null
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, GatewayService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "gateway").setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle("Health Bridge").setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }
    override fun onDestroy() {
        scope.cancel()
        app.relay.stop()
        super.onDestroy()
    }
}
