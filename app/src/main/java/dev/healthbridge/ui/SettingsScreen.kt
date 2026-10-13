package dev.healthbridge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import dev.healthbridge.HealthBridgeApplication
import dev.healthbridge.data.Settings
import dev.healthbridge.relay.RelayState
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(app: HealthBridgeApplication, settings: Settings, relay: RelayState, busy: Boolean,
                   permissions: () -> Unit, manageHealth: () -> Unit, start: () -> Unit, stop: () -> Unit,
                   pair: (String, String) -> Unit, run: (suspend () -> Unit) -> Unit) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var retention by remember(settings.retentionDays) { mutableStateOf(settings.retentionDays.toString()) }
    var clear by remember { mutableStateOf(false) }
    BackHandler(page != null) { page = null }
    if (page == null) {
        NavigationRow("Instance pairing", if (settings.paired) "Paired: ${relay.label}" else "Not paired") { page = "pairing" }
        NavigationRow("Connect ChatGPT", "Private GPT Actions setup") { page = "chatgpt" }
        NavigationRow("Security alerts", "Device sessions, first use and rejected credentials") { page = "security" }
        NavigationRow("Health Connect", "Permissions and read/write checks") { page = "health" }
        NavigationRow("Samsung Health", "Synchronization setup guide") { page = "samsung" }
        NavigationRow("Connection", "Manual start only") { page = "gateway" }
        NavigationRow("Appearance", settings.theme) { page = "theme" }
        NavigationRow("Activity history", "Keep ${settings.retentionDays} days") { page = "history" }
        NavigationRow("About", "Samsung ChatGPT Health Bridge 1.0.0") { page = "about" }
    } else {
        SecondaryAction("Back to Settings") { page = null }
        when (page) {
            "pairing" -> PairingPanel(app, settings, relay, busy, pair, run)
            "chatgpt" -> ChatGptSetup()
            "security" -> {
                val events by app.database.dao().securityEvents().collectAsStateWithLifecycle(initialValue = emptyList())
                SecurityEventsPanel(events)
            }
            "health" -> {
                Panel("Health permissions") {
                    Text(if (app.health.available()) "Health Connect is available." else "Install or update Health Connect.")
                    PrimaryAction("Grant health permissions", onClick = permissions)
                    SecondaryAction("Open system permissions", onClick = manageHealth)
                }
                HealthTestPanel(app, settings.testRecordId, busy, run)
            }
            "samsung" -> Panel("Samsung Health") {
                Text("Open Samsung Health, then Settings and Health Connect. Enable synchronization for the data you want to use.")
                Text("Return here and grant HealthBridge read/write permissions. Create a test entry, then check Samsung Health separately.")
                Text("A successful Health Connect write does not prove Samsung Health displays the record.")
            }
            "gateway" -> {
                GatewayPanel(relay, settings.paired, 0, busy, start, stop)
            }
            "theme" -> Panel("Appearance") {
                listOf("System", "Light", "Dark").forEach { value ->
                    if (settings.theme == value) PrimaryAction("$value (selected)", enabled = !busy) { run { app.settings.setTheme(value) } }
                    else SecondaryAction(value, enabled = !busy) { run { app.settings.setTheme(value) } }
                }
            }
            "history" -> Panel("Activity history") {
                QuietInput(retention, { retention = it }, "Keep history for 1-90 days")
                PrimaryAction("Save retention", enabled = !busy) { run {
                    val days = retention.toIntOrNull() ?: throw IllegalArgumentException("Invalid retention")
                    app.settings.setRetention(days)
                    app.database.dao().prune(System.currentTimeMillis() - days * 86400000L)
                } }
                SecondaryAction("Clear activity history", enabled = !busy) { clear = true }
                Text("Logs contain actions and outcomes, not nutrition or weight payloads.")
            }
            "about" -> Panel("Samsung ChatGPT Health Bridge") {
                Text("Version 1.0.0\nOfficial Android Health Connect SDK\nPrivate single-owner instance with ChatGPT Actions")
                Text("No MCP, analytics or copied health database. Nutrition and weight can be added and read; steps are read-only.")
                Text("Authorized adds need no prompt. Deletion is limited to HealthBridge-owned records and requires confirmation on this phone.")
                Text("Personal, unofficial utility. Not affiliated with Samsung or OpenAI.")
            }
        }
    }
    if (clear) Confirmation("Clear activity history?", "This deletes local activity entries, not health records.",
        { clear = false }, { run { app.database.dao().clearAudit(); clear = false } })
}
