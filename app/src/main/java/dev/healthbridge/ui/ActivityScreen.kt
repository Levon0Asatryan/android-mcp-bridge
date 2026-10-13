package dev.healthbridge.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import dev.healthbridge.data.AuditLogEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun localTime(timestamp: Long): String = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))

@Composable
fun ActivityScreen(logs: List<AuditLogEntity>, clientId: String? = null) {
    var filter by remember { mutableStateOf("All") }
    SingleChoiceSegmentedButtonRow {
        listOf("All", "Reads", "Writes", "Denied").forEachIndexed { index, name ->
            SegmentedButton(filter == name, { filter = name },
                SegmentedButtonDefaults.itemShape(index, 4)) { Text(name) }
        }
    }
    val filtered = logs.filter { (clientId == null || it.clientId == clientId) && when (filter) {
        "Reads" -> it.operation.startsWith("get_")
        "Writes" -> it.operation.contains("add_") || it.operation.contains("write")
        "Denied" -> it.result == "Denied"
        else -> true
    } }
    if (filtered.isEmpty()) Text("No matching activity.")
    filtered.forEach {
        ListItem(headlineContent = { Text("${it.clientName}: ${it.operation.replace('_', ' ')}") },
            supportingContent = { Text("${localTime(it.timestamp)}  ${it.errorCategory.orEmpty()}") },
            trailingContent = { Text(it.result) })
    }
}
