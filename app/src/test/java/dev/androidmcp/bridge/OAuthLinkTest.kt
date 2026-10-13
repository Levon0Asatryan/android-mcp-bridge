package dev.androidmcp.bridge

import dev.androidmcp.bridge.api.*
import dev.androidmcp.bridge.relay.*
import dev.androidmcp.bridge.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OAuthLinkTest {
    private val stepsRange = obj("start_time" to text("2026-01-01T00:00:00Z"), "end_time" to text("2026-01-02T00:00:00Z"))
    private fun binding(grant: LinkGrant, scopes: Set<String> = grant.client.scopeSet(), oauthClient: String = "chatgpt-client",
                        version: Int = 1, generation: Int = 3, clientId: String = grant.client.id) =
        OAuthBinding(oauthClient, clientId, version, generation, scopes)

    @Test fun phoneIndependentlyChecksEveryBindingField() = runTest {
        var generation = 3
        val manager = ClientManager(MemoryStore(), testTokens()) { generation }
        val grant = manager.createOAuthLink("chatgpt-client", setOf("health.steps.read", "bridge.status"), 3)
        val handler = HealthActions(FakeHealth(), manager, FakeAudit()) { true }
        suspend fun steps(auth: OAuthBinding?) = handler.execute(grant.secret, "get_steps", stepsRange, null, auth)
        assertEquals(42, steps(binding(grant))["total_steps"]!!.jsonPrimitive.int)
        // No binding, or a binding for another client/grant/pairing, is refused before any health access.
        for (bad in listOf(null, binding(grant, oauthClient = "other-client"), binding(grant, version = 2),
            binding(grant, generation = 4), binding(grant, clientId = "00000000-0000-0000-0000-000000000000"),
            binding(grant, scopes = setOf("health.steps.read", "health.weight.read")))) {
            assertThrowsSuspend<AuthenticationException> { steps(bad) }
        }
        // Token scope must include the operation even if the phone record allows it.
        assertEquals("permission_denied", steps(binding(grant, scopes = setOf("bridge.status")))["error_category"]!!.jsonPrimitive.content)
        // Re-pairing (new generation) invalidates the link on the phone.
        generation = 4
        assertThrowsSuspend<AuthenticationException> { steps(binding(grant)) }
    }

    @Test fun tokenClientsRejectOAuthEnvelopesAndLinkedClientsOnlyNarrow() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens()) { 1 }
        val (_, token) = manager.create("Actions", setOf(ClientPermission.STEPS_READ))
        val handler = HealthActions(FakeHealth(), manager, FakeAudit()) { true }
        val fake = OAuthBinding("chatgpt-client", "00000000-0000-0000-0000-000000000000", 1, 1, setOf("health.steps.read"))
        assertThrowsSuspend<AuthenticationException> { handler.execute(token, "get_steps", stepsRange, null, fake) }
        val grant = manager.createOAuthLink("chatgpt-client", setOf("health.steps.read"), 1)
        assertThrowsSuspend<IllegalArgumentException> {
            manager.update(grant.client.id, "x", true, setOf(ClientPermission.STEPS_READ, ClientPermission.WEIGHT_READ)) }
        assertThrowsSuspend<IllegalArgumentException> { manager.regenerate(grant.client.id) }
        assertEquals(setOf("health.steps.read"), manager.registrations().single { it.clientId == grant.client.id }.oauth!!.scopes)
    }

    @Test fun revocationDuringPendingDeleteIsEnforced() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens()) { 2 }
        val grant = manager.createOAuthLink("chatgpt-client", setOf("health.nutrition.delete"), 2)
        val approval = PhoneApproval()
        val handler = HealthActions(FakeHealth(), manager, FakeAudit(), approval) { true }
        val result = async {
            try { handler.execute(grant.secret, "delete_nutrition", obj("record_id" to text("owned")), null, binding(grant, generation = 2)); false }
            catch (e: AuthenticationException) { true }
        }
        yield()
        manager.revoke(grant.client.id)
        assertTrue(manager.registrations().none { it.clientId == grant.client.id })
        approval.respond(requireNotNull(approval.pending.value).id, true)
        assertTrue(result.await())
    }

    @Test fun linkApprovalCannotWidenAndTimesOutClosed() = runTest {
        val approval = LinkApproval()
        val request = LinkRequest("11111111-1111-1111-1111-111111111111", "123456", "a".repeat(64), "chatgpt-client",
            "chatgpt.com", setOf("health.steps.read"), System.currentTimeMillis() + 60_000)
        val widened = async { approval.request(request) }
        yield()
        assertEquals(LinkDecision(false), approval.request(request.copy(linkId = "other")))
        approval.respond(request.linkId, LinkDecision(true, setOf("health.steps.read", "health.weight.read")))
        assertFalse(widened.await().approved)
        val timedOut = async { approval.request(request) }
        yield()
        assertNotNull(approval.pending.value)
        assertFalse(timedOut.await().approved)
        assertNull(approval.pending.value)
    }

    @Test fun relayParsesStrictLinkRequestsAndAuthEnvelopes() {
        val now = System.currentTimeMillis()
        val link = JsonObject(mapOf("type" to text("link_request"), "version" to JsonPrimitive(1),
            "link_id" to text("11111111-1111-1111-1111-111111111111"), "code" to text("123456"), "binding" to text("b".repeat(64)),
            "oauth_client_id" to text("chatgpt-client"), "redirect_host" to text("chatgpt.com"),
            "scopes" to JsonArray(listOf(text("health.steps.read"))), "expires_at" to JsonPrimitive(now + 120_000)))
        assertNotNull(RelayProtocol.linkRequest(link, now))
        assertNull(RelayProtocol.linkRequest(JsonObject(link + ("scopes" to JsonArray(listOf(text("admin"))))), now))
        assertNull(RelayProtocol.linkRequest(JsonObject(link + ("expires_at" to JsonPrimitive(now + 3_600_000))), now))
        assertNull(RelayProtocol.linkRequest(JsonObject(link + ("extra" to text("x"))), now))
        val base = mapOf("type" to text("command"), "version" to JsonPrimitive(1), "session_id" to text("s1"),
            "request_id" to text("req_1"), "expires_at" to text(java.time.Instant.ofEpochMilli(now + 20_000).toString()),
            "operation" to text("get_steps"), "client_token" to text("amb.x.y"), "arguments" to JsonObject(emptyMap()))
        val auth = mapOf("kind" to text("oauth"), "oauth_client_id" to text("chatgpt-client"),
            "client_id" to text("11111111-1111-1111-1111-111111111111"), "grant_version" to JsonPrimitive(1),
            "pairing_generation" to JsonPrimitive(1), "scopes" to JsonArray(listOf(text("health.steps.read"))))
        val parsed = RelayProtocol.command(JsonObject(base + ("auth" to JsonObject(auth))), "s1", now).second.getOrThrow()
        assertEquals(setOf("health.steps.read"), parsed.auth!!.scopes)
        val bad = RelayProtocol.command(JsonObject(base + ("auth" to JsonObject(auth + ("kind" to text("bearer"))))), "s1", now).second
        assertEquals("unauthorized", (bad.exceptionOrNull() as RelayRejection).category)
    }
}
