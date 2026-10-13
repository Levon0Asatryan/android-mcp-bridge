package dev.healthbridge.ui

import android.content.*
import android.os.PersistableBundle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

@Composable
fun TokenDialog(token: String, dismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = dismiss, title = { Text("Save your access token") },
        text = { Text("$token\n\nShown once. Expires in 90 days. Keep it private. In your private GPT's Actions settings, choose API key / Bearer authentication and paste this token. Do not paste it into a conversation.") },
        confirmButton = { TextButton(onClick = {
            val clip = ClipData.newPlainText("HealthBridge token", token)
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        }) { Text("Copy token") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Done") } })
}
