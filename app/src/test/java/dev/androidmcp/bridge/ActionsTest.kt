package dev.androidmcp.bridge

import dev.androidmcp.bridge.api.*
import dev.androidmcp.bridge.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ActionsTest {
    @Test fun actionsRequireTokenAndRespectReadWriteGrants() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (identity, token) = manager.create("ChatGPT", emptySet())
        val health = FakeHealth()
        val audit = FakeAudit()
        val handler = HealthActions(health, manager, audit) { true }
        assertThrowsSuspend<AuthenticationException> { handler.execute("", "get_gateway_status", obj()) }
        assertTrue(handler.execute(token, "get_gateway_status", obj())["running"]!!.jsonPrimitive.boolean)
        suspend fun steps() = handler.execute(token, "get_steps",
            obj("start_time" to text("2026-01-01T00:00:00Z"), "end_time" to text("2026-01-02T00:00:00Z")))
        assertEquals("permission_denied", steps()["error_category"]!!.jsonPrimitive.content)
        manager.update(identity.id, identity.name, true, setOf(ClientPermission.STEPS_READ))
        assertEquals(42, steps()["total_steps"]!!.jsonPrimitive.int)
        health.grants = emptySet()
        assertEquals("health_permission_denied", steps()["error_category"]!!.jsonPrimitive.content)
        manager.revoke(identity.id)
        assertThrowsSuspend<AuthenticationException> { handler.execute(token, "get_gateway_status", obj()) }
    }

    @Test fun deletionsRequirePhoneApprovalAndRecheckRevocation() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (identity, token) = manager.create("ChatGPT", setOf(ClientPermission.NUTRITION_DELETE))
        val approval = PhoneApproval()
        val handler = HealthActions(FakeHealth(), manager, FakeAudit(), approval) { true }
        val arguments = obj("record_id" to text("owned-record"))
        val denied = async { handler.execute(token, "delete_nutrition", arguments) }
        yield()
        approval.respond(requireNotNull(approval.pending.value).id, false)
        assertTrue(denied.await().toString().contains("approval_denied"))

        val revoked = async {
            try { handler.execute(token, "delete_nutrition", arguments); false }
            catch (e: AuthenticationException) { true }
        }
        yield()
        manager.revoke(identity.id)
        approval.respond(requireNotNull(approval.pending.value).id, true)
        assertTrue(revoked.await())
    }

    @Test fun deletionApprovalTimesOutClosed() = runTest {
        val approval = PhoneApproval()
        val result = async {
            try { approval.request("ChatGPT", "nutrition", "record"); false }
            catch (e: ApprovalDeniedException) { true }
        }
        yield()
        assertNotNull(approval.pending.value)
        assertTrue(result.await())
        assertNull(approval.pending.value)
    }

    @Test fun deletionSuccessRequiresExplicitApproval() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (_, token) = manager.create("ChatGPT", setOf(ClientPermission.WEIGHT_DELETE))
        val approval = PhoneApproval()
        val handler = HealthActions(FakeHealth(), manager, FakeAudit(), approval) { true }
        val result = async { handler.execute(token, "delete_weight", obj("record_id" to text("owned-weight"))) }
        yield()
        approval.respond(requireNotNull(approval.pending.value).id, true)
        assertTrue(result.await().toString().contains("\"deleted_record_id\":\"owned-weight\""))
    }
}
