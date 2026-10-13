package dev.androidmcp.bridge.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import dev.androidmcp.bridge.BridgeApplication
import dev.androidmcp.bridge.health.*
import java.time.OffsetDateTime
import java.util.UUID

@Composable
fun HealthTestPanel(app: BridgeApplication, testId: String?, busy: Boolean, run: (suspend () -> Unit) -> Unit) {
    var pending by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    Panel("Health Connect checks") {
        SecondaryAction("Test read access", enabled = !busy) { run {
            val now = OffsetDateTime.now()
            val records = app.health.nutrition(now.minusDays(1).toString(), now.toString())
            result = "Read access works: ${records.size} nutrition records in the last 24 hours."
        } }
        PrimaryAction("Test nutrition write", enabled = !busy && testId == null) { pending = "write" }
        testId?.let {
            Text("Test record: $it")
            SecondaryAction("Delete test record", enabled = !busy) { pending = "delete" }
        }
        result?.let { Text(it) }
    }
    pending?.let { operation ->
        Confirmation(if (operation == "write") "Create test nutrition?" else "Delete test record?",
            "Only a clearly labeled Android MCP Bridge test record is affected. Samsung Health visibility is not automatically verified.",
            { pending = null }, {
                pending = null
                run {
                    if (operation == "delete") {
                        app.health.deleteTestRecord(requireNotNull(testId))
                        app.settings.setTestRecord(null)
                        result = "Test record deleted."
                    } else {
                        val written = app.health.addNutrition(
                            NutritionEntry("Android MCP Bridge test - delete after checking", "snack", deviceTimestamp(), 100.0, 2.0, 15.0, 3.0),
                            "androidmcp-test:${UUID.randomUUID()}")
                        app.settings.setTestRecord(written.record_id)
                        result = "Written to Health Connect.\nRecord: ${written.record_id}\nRead-back pending. Samsung Health visibility: not verified."
                        app.health.readTestRecord(written.record_id)
                        result = "Written and read back from Health Connect.\nRecord: ${written.record_id}\nSamsung Health visibility: not verified. Check Samsung Health separately."
                        app.audit.record(null, "test_nutrition_write_readback", "Success")
                    }
                }
            })
    }
}
