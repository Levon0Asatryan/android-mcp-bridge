package dev.healthbridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.healthbridge.data.Settings

@Composable
fun BridgeUi(model: UiModel, permissions: () -> Unit, manageHealth: () -> Unit, start: () -> Unit, stop: () -> Unit,
             approve: (String) -> Unit, pair: (String, String) -> Unit, approveLink: (String, Set<String>) -> Unit = { _, _ -> }) {
    val app = model.app
    val ui by model.state.collectAsStateWithLifecycle()
    val sharedText by model.sharedText.collectAsStateWithLifecycle()
    val deletion by app.phoneApproval.pending.collectAsStateWithLifecycle()
    val link by app.linkApproval.pending.collectAsStateWithLifecycle()
    val clients by app.database.dao().clients().collectAsStateWithLifecycle(initialValue = emptyList())
    val logs by app.database.dao().audit().collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val relay by app.relay.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableIntStateOf(0) }
    var connection by rememberSaveable { mutableStateOf(false) }
    BackHandler(connection) { connection = false }
    var addingMeal by remember { mutableStateOf(false) }
    var clientFilter by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sharedText) { if (sharedText != null) { screen = 0; addingMeal = true } }
    val dark = settings.theme == "Dark" || (settings.theme == "System" && isSystemInDarkTheme())
    val palette = if (dark) darkColorScheme(primary = Color.White, onPrimary = Color.Black,
        background = Color.Black, surface = Color(0xFF1C1C1E), secondary = Color(0xFFCCCCCC),
        onSurfaceVariant = Color(0xFFA0A0A0))
        else lightColorScheme(primary = Color(0xFF171717), onPrimary = Color.White,
            background = Color(0xFFF2F2F4), surface = Color.White,
            secondary = Color(0xFF555555), onSurfaceVariant = Color(0xFF707070))
    val labels = listOf("Home", "Apps", "Activity", "Settings")
    val scroll = rememberScrollState()
    LaunchedEffect(screen, connection) { scroll.scrollTo(0) }
    BackHandler(screen != 0 && !connection) { screen = 0; clientFilter = null }
    val type = Typography().let { it.copy(
        headlineLarge = it.headlineLarge.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
        titleMedium = it.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp)) }
    MaterialTheme(colorScheme = palette, typography = type) {
        Scaffold(containerColor = palette.background, bottomBar = {
            BottomTabs(screen) { screen = it; clientFilter = null; connection = false }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(if (connection) "Connect ChatGPT" else if (screen == 0) "Health Bridge" else if (screen == 1) "Connected apps" else labels[screen],
                    style = MaterialTheme.typography.headlineLarge)
                if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (connection) {
                    SecondaryAction("Back to Home") { connection = false }
                    ChatGptSetup()
                } else when (screen) {
                    0 -> {
                        Text("Samsung ChatGPT Health Bridge", color = palette.onSurfaceVariant)
                        Panel("ChatGPT") {
                            Text("Connect ChatGPT as a private plugin.", style = MaterialTheme.typography.titleMedium)
                            Text("Read health data, add meals and remove your entries from a ChatGPT conversation.",
                                color = palette.onSurfaceVariant)
                            PrimaryAction("Set up ChatGPT") { connection = true }
                            SecondaryAction(if (addingMeal) "Close manual import" else "Import a meal manually") { addingMeal = !addingMeal }
                        }
                        if (addingMeal) MealComposer(app, ui.busy, model::run, sharedText)
                        NavigationRow("Health permissions",
                            if (app.health.available()) "${ui.granted} of ${dev.healthbridge.health.HealthConnectManager.permissions.size} permissions enabled" else "Health Connect unavailable", permissions)
                        GatewayPanel(relay, settings.paired, clients.count { it.enabled && it.verifier != null }, ui.busy, start, stop)
                        NavigationRow("Application access", "${clients.size} registered applications") { screen = 1 }
                        Text("Recent activity", style = MaterialTheme.typography.titleMedium)
                        if (logs.isEmpty()) Text("No activity yet.", color = palette.onSurfaceVariant)
                        logs.take(3).forEach { Text("${it.clientName}: ${it.operation.replace('_', ' ')} - ${it.result}") }
                    }
                    1 -> ClientScreen(app, clients, ui.busy, model::run) { clientFilter = it; screen = 2 }
                    2 -> ActivityScreen(logs, clientFilter)
                    3 -> SettingsScreen(app, settings, relay, ui.busy, permissions, manageHealth, start, stop, pair, model::run)
                }
            }
        }
        ui.error?.let { message ->
            AlertDialog(onDismissRequest = model::dismissError, title = { Text("Action failed") }, text = { Text(message) },
                confirmButton = { TextButton(onClick = model::dismissError) { Text("Done") } })
        }
        link?.let { request -> LinkDialog(request, { app.linkApproval.respond(request.linkId, dev.healthbridge.security.LinkDecision(false)) }, approveLink) }
        deletion?.let { request ->
            Confirmation("Delete ${request.recordType} record?",
                "${request.clientName} requested deletion of record ${request.recordId}. Only a HealthBridge-owned record can be deleted. This cannot be undone.",
                { app.phoneApproval.respond(request.id, false) },
                { approve(request.id) })
        }
    }
}
