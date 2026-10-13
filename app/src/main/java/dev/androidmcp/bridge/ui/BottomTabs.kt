package dev.androidmcp.bridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.androidmcp.bridge.R

@Composable
fun BottomTabs(selected: Int, select: (Int) -> Unit) {
    val labels = listOf("Home", "Apps", "Activity", "Approvals", "Settings")
    val icons = listOf(R.drawable.tab_home, R.drawable.tab_apps, R.drawable.tab_activity, R.drawable.tab_approvals, R.drawable.tab_settings)
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Row(Modifier.fillMaxWidth()) {
                labels.forEachIndexed { index, label ->
                    val color = if (selected == index) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(Modifier.weight(1f).selectable(selected == index, role = Role.Tab, onClick = { select(index) })
                        .heightIn(min = 64.dp).padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(painterResource(icons[index]), null, Modifier.size(24.dp), tint = color)
                        Text(label, color = color, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
