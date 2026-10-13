package dev.androidmcp.bridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.androidmcp.bridge.data.ClientEntity
import dev.androidmcp.bridge.security.*

@Composable
fun ClientEditor(client: ClientEntity?, dismiss: () -> Unit, save: (String, Boolean, Set<ClientPermission>) -> Unit) {
    var name by remember { mutableStateOf(client?.name ?: "") }
    var enabled by remember { mutableStateOf(client?.enabled ?: true) }
    var allowed by remember { mutableStateOf(client?.allowed() ?: emptySet()) }
    var confirm by remember { mutableStateOf(false) }
    if (confirm) {
        AlertDialog(onDismissRequest = { confirm = false }, title = { Text(if (client == null) "Generate credential?" else "Save access changes?") },
            text = { Text("Allow ${name.trim()} to use only the selected permissions. ${if (client == null) "The access token will be displayed once." else ""}") },
            confirmButton = { TextButton(onClick = { confirm = false; save(name.trim(), enabled, allowed) }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    } else AlertDialog(onDismissRequest = dismiss,
        title = { Text(if (client == null) "Add application" else "Application access") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietInput(name, { if (it.length <= 80) name = it }, "Application name")
                if (client != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Enabled"); Switch(enabled, { enabled = it })
                }
                ClientPermission.entries.forEach { permission ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(permission.label, Modifier.weight(1f))
                        Switch(permission in allowed, { checked -> allowed = if (checked) allowed + permission else allowed - permission })
                    }
                }
                Text("Names do not establish trust. Credentials and these permissions control access.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { confirm = true }, enabled = name.isNotBlank()) { Text(if (client == null) "Create" else "Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
