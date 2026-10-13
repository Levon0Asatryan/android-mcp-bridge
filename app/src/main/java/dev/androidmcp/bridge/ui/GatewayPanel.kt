package dev.androidmcp.bridge.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import dev.androidmcp.bridge.relay.RelayState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun GatewayPanel(relay: RelayState, paired: Boolean, clientCount: Int, busy: Boolean,
                 start: () -> Unit, stop: () -> Unit) {
    val relayActive = relay !is RelayState.Stopped && relay !is RelayState.NotPaired && relay !is RelayState.Failed
    val running = relayActive
    Panel("Phone access") {
        Text(when {
            relay is RelayState.Connected -> "Connected to your instance"
            relay is RelayState.Waiting -> "Disconnected: ${relay.reason}. Retrying at " +
                Instant.ofEpochMilli(relay.retryAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            relay is RelayState.Paused -> "Paused: ${relay.reason}"
            relay is RelayState.Failed -> relay.reason
            relay is RelayState.Connecting -> "Connecting..."
            !paired -> "Not paired. Pair with your instance in Settings."
            else -> "Stopped"
        }, color = if (relay is RelayState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text("$clientCount authorized applications", color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryAction(if (running) "Stop phone access" else "Start phone access",
            enabled = !busy && (running || paired), onClick = if (running) stop else start)
        Text("Health requests are refused while the phone is locked or battery saver is on; alarms, notifications and speech still work. Stop here at any time.",
            style = MaterialTheme.typography.bodySmall)
    }
}
