package dev.androidmcp.bridge.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.androidmcp.bridge.BridgeApplication
import dev.androidmcp.bridge.data.SecurityEventEntity
import dev.androidmcp.bridge.data.Settings
import dev.androidmcp.bridge.describeSecurityEvent
import dev.androidmcp.bridge.relay.KeystoreDeviceIdentity
import dev.androidmcp.bridge.relay.RelayState

@Composable
fun PairingPanel(app: BridgeApplication, settings: Settings, relay: RelayState, busy: Boolean,
                 pair: (String, String) -> Unit, run: (suspend () -> Unit) -> Unit) {
    var forget by remember { mutableStateOf(false) }
    if (settings.paired) Panel("Paired instance") {
        Text(settings.instanceUrl.orEmpty())
        Text("Device key ${settings.deviceKid.orEmpty().take(10)}... (${KeystoreDeviceIdentity.securityLevel()})",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Pairing generation ${settings.pairingGeneration}. Connection: ${relay.label}",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SecondaryAction("Forget pairing on this phone", enabled = !busy) { forget = true }
        Text("The instance keeps this phone's public key and rejects any other device until its operator resets pairing.",
            style = MaterialTheme.typography.bodySmall)
    } else Panel("Pair with your instance") {
        // Held only in composition memory: not saved to instance state, settings, logs or the database.
        var url by remember { mutableStateOf("") }
        var secret by remember { mutableStateOf("") }
        QuietInput(url, { url = it.take(200) }, "Instance HTTPS address")
        OutlinedTextField(secret, { secret = it.take(256) }, Modifier.fillMaxWidth(), label = { Text("Owner bootstrap secret") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
        PrimaryAction("Pair this phone", enabled = !busy && url.isNotBlank() && secret.length >= 32) {
            pair(url, secret); secret = ""
        }
        Text("The secret works once and never grants health access. A secure screen lock and confirmation are required. " +
            "A non-exportable Keystore key is created on this phone; hardware backing depends on the device. Only its public part is sent.",
            style = MaterialTheme.typography.bodySmall)
    }
    if (forget) Confirmation("Forget pairing?",
        "This stops the connection and deletes this phone's device key. Pairing again requires a pairing reset and a new bootstrap secret in the instance configuration.",
        { forget = false }, {
            forget = false
            run {
                app.relay.stop()
                app.settings.clearPairing()
                KeystoreDeviceIdentity.delete()
                app.recordLocalEvent("pairing_forgotten")
            }
        })
}

@Composable
fun SecurityEventsPanel(events: List<SecurityEventEntity>) = Panel("Security alerts") {
    if (events.isEmpty()) Text("No security events yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    events.take(50).forEach {
        val text = describeSecurityEvent(it.type, it.detail, it.count) ?: it.type.replace('_', ' ')
        Text("${localTime(it.timestamp)} · ${if (it.source == "instance") "Instance" else "Phone"}: $text")
    }
    Text("Instance events contain only type, time and count: no tokens, network addresses or health record identifiers.",
        style = MaterialTheme.typography.bodySmall)
}
