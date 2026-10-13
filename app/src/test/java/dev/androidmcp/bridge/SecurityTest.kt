package dev.androidmcp.bridge

import dev.androidmcp.bridge.api.*
import dev.androidmcp.bridge.data.*
import dev.androidmcp.bridge.health.*
import dev.androidmcp.bridge.security.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class MemoryStore : ClientStore {
    val clients = mutableMapOf<String, ClientEntity>()
    val receipts = mutableMapOf<String, WriteReceipt>()
    override suspend fun all() = clients.values.toList()
    override suspend fun find(id: String) = clients[id]
    override suspend fun save(client: ClientEntity) { clients[client.id] = client }
    override suspend fun delete(id: String) { clients.remove(id) }
    override suspend fun receipt(clientId: String, operation: String, keyHash: String) = receipts["$clientId:$operation:$keyHash"]
    override suspend fun saveReceipt(receipt: WriteReceipt) { receipts["${receipt.clientId}:${receipt.operation}:${receipt.keyHash}"] = receipt }
}
class FakeHealth : HealthRepository {
    var grants = setOf("android.permission.health.READ_NUTRITION", "android.permission.health.WRITE_NUTRITION",
        "android.permission.health.READ_WEIGHT", "android.permission.health.WRITE_WEIGHT", "android.permission.health.READ_STEPS")
    var writes = 0
    var failWrite = false
    var onGranted: suspend () -> Unit = {}
    var reads = 0
    override fun available() = true
    override suspend fun grantedPermissions(): Set<String> { onGranted(); return grants }
    override suspend fun nutrition(start: String, end: String) = emptyList<NutritionData>()
    override suspend fun weight(start: String, end: String) = emptyList<WeightData>()
    override suspend fun steps(start: String, end: String): Long { reads++; return 42L }
    override suspend fun addNutrition(entry: NutritionEntry, recordKey: String): WriteResult {
        writes++
        if (failWrite) error("Simulated rejection")
        return WriteResult(record_id = recordKey, timestamp = entry.timestamp)
    }
    override suspend fun addWeight(entry: WeightEntry, recordKey: String) = WriteResult(record_id = recordKey, timestamp = entry.timestamp)
    override suspend fun deleteNutrition(id: String) { }
    override suspend fun deleteWeight(id: String) { }
}
class FakeAudit : AuditSink {
    val outcomes = mutableListOf<String>()
    override suspend fun record(client: ClientEntity?, operation: String, result: String, category: String?) { outcomes += "$operation:$result:$category" }
}
fun testTokens() = TokenManager(object : CredentialProtector {
    override fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHex()
})

class SecurityTest {
    private val args = bridgeJson.parseToJsonElement("""{"meal_name":"Test","meal_type":"dinner","timestamp":"2026-01-01T20:00:00+04:00","calories_kcal":100,"protein_g":2,"carbs_g":15,"fat_g":3,"idempotency_key":"retry-1"}""").jsonObject

    @Test fun tokensRevokeAndRotate() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (client, token) = manager.create("ChatGPT", emptySet())
        assertEquals(client.id, manager.identify(token).id)
        assertFalse(client.verifier!!.contains(token))
        assertThrowsSuspend<AuthenticationException> { manager.identify(token.dropLast(1) + "!") }
        val replacement = manager.regenerate(client.id)
        assertThrowsSuspend<AuthenticationException> { manager.identify(token) }
        manager.revoke(client.id)
        assertThrowsSuspend<AuthenticationException> { manager.identify(replacement) }
    }

    @Test fun defaultAccessIsEmptyAndDisabledClientsAreRejected() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (client, token) = manager.create("ChatGPT", emptySet())
        assertTrue(client.allowed().isEmpty())
        assertThrowsSuspend<ClientPermissionException> { manager.execute(token, ClientPermission.NUTRITION_WRITE) { true } }
        manager.update(client.id, client.name, true, setOf(ClientPermission.NUTRITION_WRITE))
        assertTrue(manager.execute(token, ClientPermission.NUTRITION_WRITE) { true })
        manager.update(client.id, client.name, false, emptySet())
        assertThrowsSuspend<AuthenticationException> { manager.identify(token) }
        manager.delete(client.id)
        assertThrowsSuspend<AuthenticationException> { manager.identify(token) }
    }

    @Test fun retriesDoNotDuplicateAndHealthGrantsAreRechecked() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (_, token) = manager.create("ChatGPT", setOf(ClientPermission.NUTRITION_WRITE))
        val health = FakeHealth()
        val approval = PhoneApproval()
        val handler = HealthActions(health, manager, FakeAudit(), approval) { true }
        val first = approved(approval) { handler.execute(token, "add_nutrition", args) }
        val second = approved(approval) { handler.execute(token, "add_nutrition", args) }
        assertEquals(first, second)
        assertEquals(1, health.writes)
        val changed = JsonObject(args + ("calories_kcal" to JsonPrimitive(200)))
        assertEquals("idempotency_conflict", approved(approval) { handler.execute(token, "add_nutrition", changed) }["error_category"]!!.jsonPrimitive.content)
        health.grants = emptySet()
        assertEquals("health_permission_denied", approved(approval) { handler.execute(token, "add_nutrition", args) }["error_category"]!!.jsonPrimitive.content)
    }

    @Test fun rejectedWriteDoesNotReportSuccessAndCanRetry() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (_, token) = manager.create("ChatGPT", setOf(ClientPermission.NUTRITION_WRITE))
        val health = FakeHealth().apply { failWrite = true }
        val approval = PhoneApproval()
        val handler = HealthActions(health, manager, FakeAudit(), approval) { true }
        assertFalse(approved(approval) { handler.execute(token, "add_nutrition", args) }["success"]!!.jsonPrimitive.boolean)
        health.failWrite = false
        assertTrue(approved(approval) { handler.execute(token, "add_nutrition", args) }["success"]!!.jsonPrimitive.boolean)
        assertEquals(2, health.writes)
    }


    @Test fun expiredCredentialsCannotAuthenticate() = runTest {
        val store = MemoryStore()
        val manager = ClientManager(store, testTokens())
        val (client, token) = manager.create("ChatGPT", emptySet())
        store.save(client.copy(expiresAt = System.currentTimeMillis() - 1))
        assertThrowsSuspend<AuthenticationException> { manager.identify(token) }
        assertEquals(client.id, manager.identify(manager.regenerate(client.id)).id)
    }

    @Test fun lockedPhoneRejectsReadsBeforeHealthDataIsAccessed() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (_, token) = manager.create("ChatGPT", setOf(ClientPermission.STEPS_READ))
        val handler = HealthActions(FakeHealth(), manager, FakeAudit(), phoneUnlocked = { false }) { true }
        assertEquals("phone_locked", handler.execute(token, "get_steps", obj())["error_category"]!!.jsonPrimitive.content)
    }

    @Test fun newAndReplacementTokensExpireAfterExactlyNinetyDays() = runTest {
        val store = MemoryStore()
        val manager = ClientManager(store, testTokens())
        val lifetime = 90L * 24 * 60 * 60 * 1000
        val beforeCreate = System.currentTimeMillis()
        val (client, token) = manager.create("ChatGPT", emptySet())
        assertTrue(client.expiresAt in (beforeCreate + lifetime)..(System.currentTimeMillis() + lifetime))
        store.save(client.copy(expiresAt = System.currentTimeMillis() + 86_400_000L))
        val beforeRotate = System.currentTimeMillis()
        val replacement = manager.regenerate(client.id)
        val rotated = manager.identify(replacement)
        assertTrue(rotated.expiresAt in (beforeRotate + lifetime)..(System.currentTimeMillis() + lifetime))
        assertThrowsSuspend<AuthenticationException> { manager.identify(token) }
        store.save(rotated.copy(expiresAt = System.currentTimeMillis()))
        assertThrowsSuspend<AuthenticationException> { manager.identify(replacement) }
    }
}

suspend fun approved(approval: PhoneApproval, work: suspend () -> JsonObject): JsonObject = coroutineScope {
    val result = async { work() }
    yield()
    approval.pending.value?.let { approval.respond(it.id, true) }
    result.await()
}

suspend inline fun <reified T : Throwable> assertThrowsSuspend(block: suspend () -> Unit) {
    try { block(); fail("Expected ${T::class.simpleName}") }
    catch (e: Throwable) { if (e !is T) throw e }
}
