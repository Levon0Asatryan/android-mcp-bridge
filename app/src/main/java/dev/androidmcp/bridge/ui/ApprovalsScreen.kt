package dev.androidmcp.bridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.androidmcp.bridge.security.ApprovalRequest
import dev.androidmcp.bridge.security.LinkRequest

/** Pending owner decisions in one place: ChatGPT connection requests and record deletions. */
@Composable
fun ApprovalsScreen(
    link: LinkRequest?, deletion: ApprovalRequest?, denyLink: (String) -> Unit,
    approveLink: (String, Set<String>) -> Unit, decideDeletion: (String, Boolean) -> Unit,
) {
    if (link == null && deletion == null) {
        Panel("Nothing waiting") {
            Text("New ChatGPT connection requests and deletion requests appear here. Requests expire after about two minutes.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    link?.let { request ->
        var selected by remember(request.linkId) { mutableStateOf(request.scopes) }
        Panel("Connect ChatGPT?") {
            Text("Approve only if your browser shows code ${request.code} and you just started this connection.")
            Text("Returns to ${request.redirectHost}. Requested access:", style = MaterialTheme.typography.bodySmall)
            request.scopes.sorted().forEach { scope ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(scopeLabel(scope), Modifier.weight(1f))
                    Switch(scope in selected, { checked -> selected = if (checked) selected + scope else selected - scope })
                }
            }
            PrimaryAction("Approve", enabled = selected.isNotEmpty()) { approveLink(request.linkId, selected) }
            SecondaryAction("Deny") { denyLink(request.linkId) }
        }
    }
    deletion?.let { request ->
        Panel("Delete ${request.recordType} record?") {
            Text("${request.clientName} requested deletion of record ${request.recordId}. Only a Android MCP Bridge-owned record can be deleted. This cannot be undone.")
            PrimaryAction("Delete") { decideDeletion(request.id, true) }
            SecondaryAction("Deny") { decideDeletion(request.id, false) }
        }
    }
}
