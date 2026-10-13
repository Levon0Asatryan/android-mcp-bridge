package dev.healthbridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.healthbridge.security.ClientPermission
import dev.healthbridge.security.LinkRequest
import dev.healthbridge.security.STATUS_SCOPE

fun scopeLabel(scope: String) = if (scope == STATUS_SCOPE) "Bridge status and permissions"
    else ClientPermission.entries.firstOrNull { it.scope == scope }?.label ?: scope

/** Owner decision for one browser OAuth request; the code must match the one shown in the browser. */
@Composable
fun LinkDialog(request: LinkRequest, deny: () -> Unit, approve: (String, Set<String>) -> Unit) {
    var selected by remember(request.linkId) { mutableStateOf(request.scopes) }
    AlertDialog(onDismissRequest = deny, title = { Text("Connect ChatGPT?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Approve only if your browser shows code ${request.code} and you just started this connection.")
                Text("Returns to ${request.redirectHost}. Requested access:", style = MaterialTheme.typography.bodySmall)
                request.scopes.sorted().forEach { scope ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(scopeLabel(scope), Modifier.weight(1f))
                        Switch(scope in selected, { checked -> selected = if (checked) selected + scope else selected - scope })
                    }
                }
                Text("Expires in about two minutes. Deletions still need your confirmation each time.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { approve(request.linkId, selected) }, enabled = selected.isNotEmpty()) { Text("Approve") } },
        dismissButton = { TextButton(onClick = deny) { Text("Deny") } })
}
