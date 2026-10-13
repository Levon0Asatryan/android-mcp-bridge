package dev.androidmcp.bridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import dev.androidmcp.bridge.BridgeApplication
import dev.androidmcp.bridge.data.ClientEntity
import dev.androidmcp.bridge.security.allowed

@Composable
fun ClientScreen(app: BridgeApplication, clients: List<ClientEntity>, busy: Boolean,
                 run: (suspend () -> Unit) -> Unit, activity: (String) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ClientEntity?>(null) }
    var secret by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pair<ClientEntity, String>?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    BackHandler(selectedId != null) { selectedId = null }
    val selected = clients.firstOrNull { it.id == selectedId }
    if (selected == null) {
        Text("Choose exactly what each application can read, add or delete.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryAction("Add application", enabled = !busy) { creating = true }
        if (clients.isEmpty()) Text("No applications have access yet.")
        clients.forEach { client ->
            NavigationRow(client.name + if (client.oauthClientId != null) " (linked)" else "",
                if (!client.enabled) "Disabled" else if (client.verifier == null) "Access revoked"
                else if (client.expiresAt <= System.currentTimeMillis()) "Token expired"
                else "${client.allowed().size} permissions enabled") { selectedId = client.id }
        }
    } else {
        val client = selected
        SecondaryAction("Back to applications") { selectedId = null }
        Panel(client.name) {
            Text(if (!client.enabled) "Disabled" else if (client.verifier == null) "Credential revoked" else "Enabled")
            Text(client.allowed().joinToString { it.label }.ifEmpty { "No permissions" })
            if (client.oauthClientId != null) Text("Linked through ChatGPT sign-in. Permissions are fixed at approval; reconnect in ChatGPT to change them. Disabling or revoking ends the OAuth grant, including refresh. Reconnect in ChatGPT to restore.",
                style = MaterialTheme.typography.bodySmall)
            Text("Last access: ${client.lastAccessedAt?.let(::localTime) ?: "Never"}")
            Text("Token expires: ${localTime(client.expiresAt)}")
            Text("ID: ${client.id}", style = MaterialTheme.typography.bodySmall)
            if (client.oauthClientId == null) PrimaryAction("Edit name and permissions", enabled = !busy) { editing = client }
            SecondaryAction("View activity") { activity(client.id) }
            if (client.oauthClientId == null) SecondaryAction("Generate new token", enabled = !busy) { pending = client to "Regenerate" }
            SecondaryAction("Revoke access token", enabled = !busy && client.verifier != null) { pending = client to "Revoke" }
            SecondaryAction("Delete application", enabled = !busy) { pending = client to "Delete" }
        }
    }
    if (creating || editing != null) ClientEditor(editing, { creating = false; editing = null }) { name, enabled, allowed ->
        val current = editing
        run {
            if (current == null) secret = app.clients.create(name, allowed).second
            else app.clients.update(current.id, name, enabled, allowed)
            creating = false
            editing = null
        }
    }
    pending?.let { (client, operation) ->
        Confirmation("$operation ${client.name}?", "Existing credentials will stop working. In-flight operations finish before revocation returns.",
            { pending = null }, {
                run {
                    when (operation) {
                        "Revoke" -> app.clients.revoke(client.id)
                        "Regenerate" -> secret = app.clients.regenerate(client.id)
                        "Delete" -> { app.clients.delete(client.id); selectedId = null }
                    }
                    pending = null
                }
            })
    }
    secret?.let { TokenDialog(it) { secret = null } }
}
