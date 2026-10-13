package dev.androidmcp.bridge.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.androidmcp.bridge.BridgeApplication
import dev.androidmcp.bridge.MainActivity
import dev.androidmcp.bridge.R
import dev.androidmcp.bridge.gateway.GatewayService
import dev.androidmcp.bridge.relay.RelayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Home-screen widget: phone access status, an on/off button and the count of authorized apps. */
class PhoneAccessWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext as BridgeApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { refresh(app, app.relay.state.value, authorizedApps(app)) } finally { pending.finish() }
        }
    }

    companion object {
        suspend fun authorizedApps(app: BridgeApplication) = app.database.dao().clients().first().count { it.enabled && it.verifier != null }

        fun active(state: RelayState) = state !is RelayState.Stopped && state !is RelayState.NotPaired && state !is RelayState.Failed

        fun refresh(context: Context, state: RelayState, apps: Int) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PhoneAccessWidget::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, render(context, state, apps))
        }

        private fun render(context: Context, state: RelayState, apps: Int): RemoteViews {
            val on = active(state)
            val (label, color) = when (state) {
                is RelayState.Connected -> "Connected" to R.color.widget_ok
                is RelayState.Connecting, is RelayState.Waiting -> "Reconnecting" to R.color.widget_warn
                is RelayState.Paused -> "Paused" to R.color.widget_warn
                is RelayState.Failed -> "Action needed" to R.color.widget_bad
                RelayState.NotPaired -> "Not paired" to R.color.widget_bad
                RelayState.Stopped -> "Off" to R.color.widget_off
            }
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val toggle = if (on) PendingIntent.getService(context, 11, Intent(context, GatewayService::class.java).setAction("STOP"), flags)
                else PendingIntent.getForegroundService(context, 12, Intent(context, GatewayService::class.java), flags)
            val open = PendingIntent.getActivity(context, 13, Intent(context, MainActivity::class.java), flags)
            return RemoteViews(context.packageName, R.layout.widget_phone_access).apply {
                setTextViewText(R.id.widget_status, label)
                setTextColor(R.id.widget_status, context.getColor(color))
                setTextViewText(R.id.widget_apps, if (apps == 1) "1 app" else "$apps apps")
                setTextViewText(R.id.widget_toggle, if (on) "Turn off" else "Turn on")
                setOnClickPendingIntent(R.id.widget_toggle, toggle)
                setOnClickPendingIntent(R.id.widget_root, open)
            }
        }
    }
}
