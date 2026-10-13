package dev.androidmcp.bridge

import dev.androidmcp.bridge.api.*
import dev.androidmcp.bridge.health.*
import dev.androidmcp.bridge.security.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class ExtendedHealth : HealthRepository {
    var grants = setOf(
        "android.permission.health.READ_NUTRITION", "android.permission.health.WRITE_NUTRITION", "android.permission.health.READ_STEPS",
        "android.permission.health.READ_HYDRATION",
    )
    var writes = 0
    var updated: Pair<String, NutritionChanges>? = null
    var hydrationReads = 0
    override fun available() = true
    override suspend fun grantedPermissions() = grants
    override suspend fun nutrition(start: String, end: String) = emptyList<NutritionData>()
    override suspend fun addNutrition(entry: NutritionEntry, recordKey: String): WriteResult {
        writes++
        return WriteResult(record_id = recordKey, timestamp = entry.timestamp)
    }
    override suspend fun weight(start: String, end: String) = emptyList<WeightData>()
    override suspend fun addWeight(entry: WeightEntry, recordKey: String) = WriteResult(record_id = recordKey, timestamp = entry.timestamp)
    override suspend fun steps(start: String, end: String) = 1000L
    override suspend fun deleteNutrition(id: String) {}
    override suspend fun deleteWeight(id: String) {}
    override suspend fun updateNutrition(id: String, changes: NutritionChanges): WriteResult {
        updated = id to changes
        return WriteResult(record_id = id, timestamp = deviceTimestamp())
    }
    override suspend fun hydration(start: String, end: String): JsonObject { hydrationReads++; return obj("total_ml" to JsonPrimitive(500.0)) }
}

class ExtendedActionsTest {
    private fun meal(key: String, name: String = "Chicken") = buildJsonObject {
        put("meal_name", name); put("meal_type", "lunch"); put("timestamp", OffsetDateTime.now().minusHours(1).toString())
        put("calories_kcal", 300); put("protein_g", 40); put("carbs_g", 0); put("fat_g", 8); put("idempotency_key", key)
    }

    @Test fun batchReportsPerEntryAndRetriesDoNotDuplicate() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (_, token) = manager.create("ChatGPT", setOf(ClientPermission.NUTRITION_WRITE))
        val health = ExtendedHealth()
        val handler = HealthActions(health, manager, FakeAudit()) { true }
        val bad = JsonObject(meal("k2", "Bad") + ("calories_kcal" to JsonPrimitive(-5)))
        val args = obj("entries" to JsonArray(listOf(meal("k1"), bad)))
        val first = handler.execute(token, "add_nutrition_batch", args)
        assertEquals(1, first["succeeded"]!!.jsonPrimitive.int)
        val results = first["results"]!!.jsonArray
        assertTrue(results[0].jsonObject["success"]!!.jsonPrimitive.boolean)
        assertEquals("invalid_input", results[1].jsonObject["error_category"]!!.jsonPrimitive.content)
        handler.execute(token, "add_nutrition_batch", args)
        assertEquals(1, health.writes)
    }

    @Test fun batchRejectsDuplicateKeys() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (_, token) = manager.create("ChatGPT", setOf(ClientPermission.NUTRITION_WRITE))
        val handler = HealthActions(ExtendedHealth(), manager, FakeAudit()) { true }
        val result = handler.execute(token, "add_nutrition_batch", obj("entries" to JsonArray(listOf(meal("same"), meal("same")))))
        assertEquals("invalid_input", result["error_category"]!!.jsonPrimitive.content)
    }

    @Test fun updateNeedsWritePermissionAndPassesOnlyChanges() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (_, readOnly) = manager.create("Reader", setOf(ClientPermission.NUTRITION_READ))
        val (_, writer) = manager.create("Writer", setOf(ClientPermission.NUTRITION_WRITE))
        val health = ExtendedHealth()
        val handler = HealthActions(health, manager, FakeAudit()) { true }
        val args = obj("record_id" to text("r1"), "changes" to obj("protein_g" to JsonPrimitive(50)))
        assertEquals("permission_denied", handler.execute(readOnly, "update_nutrition", args)["error_category"]!!.jsonPrimitive.content)
        assertEquals("invalid_input", handler.execute(writer, "update_nutrition", obj("record_id" to text("r1"), "changes" to obj()))["error_category"]!!.jsonPrimitive.content)
        assertTrue(handler.execute(writer, "update_nutrition", args)["success"]!!.jsonPrimitive.boolean)
        assertEquals("r1" to NutritionChanges(protein_g = 50.0), health.updated)
    }

    @Test fun summaryGatesEverySectionIndependently() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (_, token) = manager.create("ChatGPT", setOf(ClientPermission.SUMMARY_READ, ClientPermission.STEPS_READ, ClientPermission.HYDRATION_READ, ClientPermission.SLEEP_READ))
        val health = ExtendedHealth()
        val handler = HealthActions(health, manager, FakeAudit()) { true }
        val summary = handler.execute(token, "get_daily_summary", obj("date" to text(LocalDate.now().toString())))
        fun status(name: String) = summary[name]!!.jsonObject["status"]!!.jsonPrimitive.content
        assertEquals("ok", status("steps"))
        assertEquals("ok", status("hydration"))
        assertEquals("health_permission_not_granted", status("sleep"))
        assertEquals("not_permitted", status("nutrition"))
        assertEquals("not_permitted", status("heart_rate"))
        assertEquals(1, health.hydrationReads)
    }

    @Test fun summaryRejectsFutureAndMalformedDates() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (_, token) = manager.create("ChatGPT", setOf(ClientPermission.SUMMARY_READ))
        val handler = HealthActions(ExtendedHealth(), manager, FakeAudit()) { true }
        for (date in listOf(LocalDate.now().plusDays(2).toString(), "2025-13-40", "yesterday")) {
            assertEquals("invalid_input", handler.execute(token, "get_daily_summary", obj("date" to text(date)))["error_category"]!!.jsonPrimitive.content)
        }
    }

    @Test fun allNewOperationsHaveClientPermissions() {
        assertEquals(ClientPermission.SUMMARY_READ, HealthActions.permissions["get_data_freshness"])
        assertEquals(ClientPermission.BODY_WRITE, HealthActions.permissions["add_body_measurement"])
        assertTrue(ClientPermission.entries.all { it.scope in OAUTH_SCOPES })
    }
}
