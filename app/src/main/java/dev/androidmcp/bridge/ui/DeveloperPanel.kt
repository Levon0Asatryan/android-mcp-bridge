package dev.androidmcp.bridge.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.androidmcp.bridge.BridgeApplication
import kotlinx.coroutines.delay

@Composable
fun DeveloperPanel(app: BridgeApplication, busy: Boolean, run: (suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    val until by app.developer.unlockedUntil.collectAsState()
    var remaining by remember { mutableStateOf(app.developer.remainingMillis()) }
    LaunchedEffect(until) { while (true) { remaining = app.developer.remainingMillis(); delay(1_000) } }
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    Panel("Developer access") {
        Text("Lets a linked AI run shell commands on this phone, like adb shell over USB. It is locked by default and each unlock lasts 10 minutes.")
        if (remaining > 0) {
            Text("Unlocked: ${remaining / 60_000}:${"%02d".format(remaining / 1000 % 60)} left", color = MaterialTheme.colorScheme.primary)
            SecondaryAction("Lock now") { app.developer.lock(); remaining = 0 }
        } else {
            Text("Locked", color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrimaryAction("Unlock for 10 minutes", enabled = !busy) {
                app.authenticator?.invoke("Unlock developer access", "Allow AI shell access for 10 minutes") { ok ->
                    if (ok) { app.developer.unlock(); run { app.recordLocalEvent("developer_unlocked", null) } }
                } ?: run { note = "Device authentication is unavailable." }
            }
        }
        Text("A client also needs the Developer shell permission in its Application settings.", style = MaterialTheme.typography.bodySmall)
    }
    Panel("Connect to the phone's debug daemon") {
        Text("1. Settings > Developer options > Wireless debugging: turn on (Wi-Fi needed).")
        Text("2. Tap Pair device with pairing code. Keep that dialog open (use split-screen) and enter its port and 6-digit code here.")
        SecondaryAction("Open Developer options") {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        QuietInput(port, { port = it.filter(Char::isDigit).take(5) }, "Pairing port")
        QuietInput(code, { code = it.filter(Char::isDigit).take(6) }, "Pairing code")
        PrimaryAction("Pair", enabled = !busy && port.isNotEmpty() && code.length == 6) {
            run {
                val ok = app.adbShell.pair(port.toInt(), code)
                note = if (ok) "Paired. The phone now trusts this app's debug key." else "Pairing failed. Check the port and code, then try again."
                if (ok) { code = ""; app.recordLocalEvent("developer_paired", null) }
            }
        }
        note?.let { Text(it) }
        Text("Pairing is stored on this phone. Revoke it under Wireless debugging > Paired devices.", style = MaterialTheme.typography.bodySmall)
    }
}
