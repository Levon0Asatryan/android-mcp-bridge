package dev.healthbridge.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable
fun ChatGptSetup() {
    Panel("1. Deploy and pair") {
        Text("Deploy the backend from this project to your own account with its OAuth client settings. See docs/standalone-setup.md.")
        Text("In Settings, open Instance pairing, enter the instance HTTPS address and the bootstrap secret, and confirm with your screen lock.")
    }
    Panel("2. Start health access") {
        Text("Tap Start health access on Home. The phone connects outward to your instance; no computer is needed.")
    }
    Panel("3. Add the plugin in ChatGPT") {
        Text("In ChatGPT on the web, open Plugins, choose + then Add custom MCP server.")
        Text("Enter your instance address followed by /mcp, choose OAuth, and enter the client ID and secret you configured on the instance.")
    }
    Panel("4. Approve on this phone") {
        Text("When ChatGPT opens the sign-in page, send the approval request. This phone shows a code; approve only if it matches the browser.")
        Text("Choose the permissions, then confirm with your biometric or PIN. The request expires after two minutes.")
    }
    Panel("5. Verify in order") {
        Text("First ask ChatGPT to run bridge_connection_check. Then check status and permissions, read a small date range, add a labeled test meal, read it back and delete it.")
        Text("A plugin appearing in the mobile app does not prove that custom MCP calls work there. Verify on the web first.")
        Text("Deletions need your biometric or PIN on this phone within 20 seconds. Health access is refused while the phone is locked or battery saver is on.")
    }
    Panel("Stop access anytime") {
        Text("Stop health access on Home, or revoke the linked client under Apps. Revocation ends its OAuth grant, including refresh.")
        Text("OpenAI and the instance host see request contents in transit. This is not end-to-end encryption.")
        Text("Links expire after ${dev.healthbridge.security.ClientManager.TOKEN_LIFETIME_DAYS} days. Reconnect the plugin in ChatGPT to renew.")
    }
}
