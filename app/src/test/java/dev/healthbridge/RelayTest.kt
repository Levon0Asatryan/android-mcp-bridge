package dev.healthbridge

import dev.healthbridge.api.*
import dev.healthbridge.relay.*
import dev.healthbridge.security.*
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.random.Random

private fun softwareSigner(): JcaDeviceSigner {
    val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    return JcaDeviceSigner(pair.private, pair.public as ECPublicKey)
}
private fun decode(part: String) = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(part))).jsonObject

class RelayPolicyTest {
    private val meal = bridgeJson.parseToJsonElement("""{"meal_name":"Test","meal_type":"dinner","timestamp":"2026-01-01T20:00:00+04:00","calories_kcal":100,"protein_g":2,"carbs_g":15,"fat_g":3,"idempotency_key":"retry-1"}""").jsonObject
    private fun category(result: JsonObject) = result["error_category"]?.jsonPrimitive?.content

    @Test fun authorizedWritesRunWithoutApprovalButRequireIdempotencyKey() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (_, token) = manager.create("ChatGPT", setOf(ClientPermission.NUTRITION_WRITE))
        val health = FakeHealth(); val approval = PhoneApproval()
        val handler = HealthActions(health, manager, FakeAudit(), approval) { true }
        assertTrue(handler.execute(token, "add_nutrition", meal)["record_id"] != null)
        assertNull(approval.pending.value)
        assertEquals("invalid_input", category(handler.execute(token, "add_nutrition", JsonObject(meal - "idempotency_key"))))
        assertEquals(1, health.writes)
    }

    @Test fun failedNativeWriteIsReportedAsUncertainAndRetrySafe() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (_, token) = manager.create("ChatGPT", setOf(ClientPermission.NUTRITION_WRITE))
        val health = FakeHealth().apply { failWrite = true }
        val handler = HealthActions(health, manager, FakeAudit()) { true }
        assertEquals("outcome_unknown", category(handler.execute(token, "add_nutrition", meal)))
        health.failWrite = false
        val retry = handler.execute(token, "add_nutrition", meal)
        assertTrue(retry["record_id"]!!.jsonPrimitive.content.startsWith("healthbridge:"))
    }

    @Test fun cancellingTheCallerWithdrawsPendingDeletionAndOnlyOneMayWait() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (_, token) = manager.create("ChatGPT", setOf(ClientPermission.WEIGHT_DELETE))
        val approval = PhoneApproval()
        val handler = HealthActions(FakeHealth(), manager, FakeAudit(), approval) { true }
        val first = launch { handler.execute(token, "delete_weight", obj("record_id" to text("a"))) }
        yield()
        assertNotNull(approval.pending.value)
        assertEquals("approval_pending", category(handler.execute(token, "delete_weight", obj("record_id" to text("b")))))
        first.cancelAndJoin()
        assertNull(approval.pending.value)
        val expired = handler.execute(token, "delete_weight", obj("record_id" to text("c")), System.currentTimeMillis() + 500)
        assertEquals("approval_denied", category(expired))
    }

    @Test fun lockAndPowerSaverBlockHealthAccessAndLockDuringReadSuppressesData() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (_, token) = manager.create("ChatGPT", setOf(ClientPermission.STEPS_READ))
        val range = obj("start_time" to text("2026-01-01T00:00:00Z"), "end_time" to text("2026-01-02T00:00:00Z"))
        val saver = HealthActions(FakeHealth(), manager, FakeAudit(), powerSaving = { true }) { true }
        assertEquals("power_saving", category(saver.execute(token, "get_steps", range)))
        var checks = 0
        val lockingMidway = HealthActions(FakeHealth(), manager, FakeAudit(), phoneUnlocked = { checks++ < 2 }) { true }
        val result = lockingMidway.execute(token, "get_steps", range)
        assertEquals("phone_locked", category(result))
        assertNull(result["total_steps"])
    }

    @Test fun registrationsCarryOnlyFingerprintsOfActiveClientsAndSignalChanges() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        var changes = 0
        manager.onCredentialsChanged = { changes++ }
        val (a, tokenA) = manager.create("A", emptySet())
        val (b, _) = manager.create("B", emptySet())
        manager.update(b.id, b.name, false, emptySet())
        val registered = manager.registrations()
        assertEquals(listOf(a.id), registered.map { it.clientId })
        assertEquals(tokenFingerprint(tokenA), registered.single().fingerprint)
        assertFalse(registered.toString().contains(tokenA))
        manager.revoke(a.id)
        assertTrue(manager.registrations().isEmpty())
        assertEquals(4, changes)
    }

    @Test fun resultBudgetCountsBytesAndCharacters() {
        assertTrue(withinResultBudget("a".repeat(65_000)))
        assertFalse(withinResultBudget("€".repeat(22_000)))
        assertFalse(withinResultBudget("a".repeat(90_001)))
        val big = obj("records" to text("x".repeat(70_000)))
        val envelope = Json.parseToJsonElement(RelayProtocol.result("s", "r", big)).jsonObject
        assertEquals("result_too_large", envelope["body"]!!.jsonObject["error_category"]!!.jsonPrimitive.content)
    }

    @Test fun stateIsRecheckedAfterSuspendingPermissionLookup() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (_, token) = manager.create("ChatGPT", setOf(ClientPermission.NUTRITION_WRITE, ClientPermission.STEPS_READ))
        var unlocked = true
        var saving = false
        val health = FakeHealth()
        val handler = HealthActions(health, manager, FakeAudit(), phoneUnlocked = { unlocked }, powerSaving = { saving }) { true }
        health.onGranted = { unlocked = false }
        assertEquals("phone_locked", category(handler.execute(token, "add_nutrition", meal)))
        assertEquals(0, health.writes)
        unlocked = true
        health.onGranted = { saving = true }
        assertEquals("power_saving", category(handler.execute(token, "add_nutrition", meal)))
        assertEquals(0, health.writes)
        saving = false
        health.onGranted = { }
        assertEquals("expired_request", category(handler.execute(token, "add_nutrition", meal, System.currentTimeMillis() - 1)))
        assertEquals(0, health.writes)
    }

    @Test fun expiredRequestWaitingForClientLockDoesNotWrite() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (_, token) = manager.create("ChatGPT", setOf(ClientPermission.NUTRITION_WRITE))
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch { manager.execute(token, null) { held.complete(Unit); release.await() } }
        held.await()
        val health = FakeHealth()
        val handler = HealthActions(health, manager, FakeAudit()) { true }
        var result: JsonObject? = null
        val request = launch { result = handler.execute(token, "add_nutrition", meal, System.currentTimeMillis() - 1) }
        yield()
        release.complete(Unit)
        holder.join(); request.join()
        assertEquals("expired_request", category(result!!))
        assertEquals(0, health.writes)
    }

    @Test fun clientLimitIsExplicitRatherThanSilentTruncation() = runTest {
        val manager = ClientManager(MemoryStore(), testTokens())
        repeat(ClientManager.MAX_CLIENTS) { manager.create("Client $it", emptySet()) }
        assertEquals(ClientManager.MAX_CLIENTS, manager.registrations().size)
        assertThrowsSuspend<IllegalArgumentException> { manager.create("Overflow", emptySet()) }
    }
}

class RelayProtocolTest {
    private fun command(vararg overrides: Pair<String, JsonElement>) = JsonObject(mapOf(
        "type" to JsonPrimitive("command"), "version" to JsonPrimitive(1), "session_id" to JsonPrimitive("s1"),
        "request_id" to JsonPrimitive("req_1"), "expires_at" to JsonPrimitive(java.time.Instant.ofEpochMilli(System.currentTimeMillis() + 20_000).toString()),
        "operation" to JsonPrimitive("get_steps"), "client_token" to JsonPrimitive("hb.x.y"), "arguments" to JsonObject(emptyMap())) + overrides)
    private fun rejection(message: JsonObject) = (RelayProtocol.command(message, "s1", System.currentTimeMillis()).second
        .exceptionOrNull() as? RelayRejection)?.category

    @Test fun commandEnvelopeIsStrict() {
        assertTrue(RelayProtocol.command(command(), "s1", System.currentTimeMillis()).second.isSuccess)
        assertNull(RelayProtocol.command(command(), "other", System.currentTimeMillis()).first)
        assertEquals("protocol_error", rejection(JsonObject(command() + ("extra" to JsonPrimitive(1)))))
        assertEquals("protocol_error", rejection(command("operation" to JsonPrimitive("drop_all"))))
        assertEquals("expired_request", rejection(command("expires_at" to JsonPrimitive("2000-01-01T00:00:00Z"))))
        assertEquals("invalid_input", rejection(command("arguments" to JsonArray(emptyList()))))
        assertEquals("protocol_error", rejection(command("request_id" to JsonPrimitive("bad id"))))
        assertNull(RelayProtocol.parse("""{"type":"command","version":2}"""))
        assertEquals("protocol_error", rejection(command("expires_at" to JsonPrimitive("2099-01-01T00:00:00Z"))))
        assertEquals("invalid_input", rejection(command("arguments" to obj("value" to text("€".repeat(22_000))))))
    }

    @Test fun originsMustBeCanonical() {
        assertEquals("https://bridge.example.test", canonicalOrigin("https://Bridge.example.test/"))
        assertNull(canonicalOrigin("http://bridge.example.test"))
        assertNull(canonicalOrigin("https://bridge.example.test/path"))
        assertNull(canonicalOrigin("https://user@bridge.example.test"))
        assertNull(canonicalOrigin("http://localhost:8787"))
    }

    @Test fun backoffIsCappedWithJitter() {
        val random = Random(1)
        assertTrue(backoffMillis(0, random) in 500L..1_000L)
        assertTrue(backoffMillis(20, random) in 150_000L..300_000L)
    }

    @Test fun deviceProofsAreStandardEs256Jws() {
        val signer = softwareSigner()
        val proof = DeviceJws.connectProof(signer, "https://bridge.example.test", "n".repeat(43), 3, 1_000)
        val (header, claims, signature) = proof.split(".")
        assertEquals(setOf("alg", "typ", "kid"), decode(header).keys)
        assertEquals(DeviceJws.thumbprint(signer.publicKey), decode(header)["kid"]!!.jsonPrimitive.content)
        val body = decode(claims)
        assertEquals("https://bridge.example.test/device/connect", body["aud"]!!.jsonPrimitive.content)
        assertEquals(3, body["pairing_generation"]!!.jsonPrimitive.int)
        assertEquals(1_120L, body["exp"]!!.jsonPrimitive.long)
        val raw = Base64.getUrlDecoder().decode(signature)
        assertEquals(64, raw.size)
        val verifier = Signature.getInstance("SHA256withECDSAinP1363Format").apply { initVerify(signer.publicKey); update("$header.$claims".toByteArray()) }
        assertTrue(verifier.verify(raw))
        val pairing = DeviceJws.pairingProof(signer, "https://bridge.example.test", "c".repeat(43), 1_000).split(".")
        assertEquals(DeviceJws.jwk(signer.publicKey), decode(pairing[0])["jwk"])
        // Synthetic key material only; lets the backend suite verify Kotlin-produced proofs with jose.
        println("INTEROP_FIXTURE " + JsonObject(mapOf("jwk" to DeviceJws.jwk(signer.publicKey),
            "kid" to JsonPrimitive(DeviceJws.thumbprint(signer.publicKey)), "connect" to JsonPrimitive(proof),
            "pair" to JsonPrimitive(pairing.joinToString(".")))))
    }

    @Test fun derSignaturesWithShortIntegersArePadded() {
        val der = byteArrayOf(0x30, 0x08, 0x02, 0x02, 0x00, 0x81.toByte(), 0x02, 0x02, 0x01, 0x02)
        val raw = DeviceJws.derToJose(der)
        assertEquals(64, raw.size)
        assertEquals(0x81.toByte(), raw[31]); assertEquals(0x01.toByte(), raw[62]); assertEquals(0x02.toByte(), raw[63])
    }
}

/** Runs a real RelaySession over a Ktor WebSocket against a fake instance that follows the backend protocol. */
class RelaySessionTest {
    @Test fun authenticationFailureUsesHttpStatusAndStopsReconnect() = runBlocking {
        var origin = ""
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                post("/device/challenge") {
                    call.respondText("""{"nonce":"${"n".repeat(43)}","audience":"$origin/device/connect","pairing_generation":1}""",
                        ContentType.Application.Json)
                }
                get("/device/connect") { call.respond(HttpStatusCode.Unauthorized) }
            }
        }
        server.start(wait = false)
        origin = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}"
        val http = relayHttpClient()
        try {
            val manager = ClientManager(MemoryStore(), testTokens())
            val session = RelaySession(http, origin, 1, softwareSigner(), manager,
                HealthActions(FakeHealth(), manager, FakeAudit()) { true }, { }, null, { fail("Must not connect") })
            try {
                withTimeout(5_000) { session.run() }
                fail("Authentication must fail")
            } catch (e: RelayException) {
                assertEquals(e.message, 401, e.status)
                assertTrue(e.fatal)
            }
        } finally {
            http.close()
            server.stop(0, 1_000)
        }
    }

    @Test fun sessionRegistersClientsRelaysCommandsAndCancelsApprovalsOnDisconnect() = testApplication {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (_, token) = manager.create("ChatGPT", setOf(ClientPermission.STEPS_READ, ClientPermission.WEIGHT_DELETE))
        val approval = PhoneApproval()
        val handler = HealthActions(FakeHealth(), manager, FakeAudit(), approval) { true }
        val signer = softwareSigner()
        val seen = Channel<JsonObject>(Channel.UNLIMITED)
        val serverOut = Channel<String>(Channel.UNLIMITED)
        var authorization: String? = null
        install(io.ktor.server.websocket.WebSockets)
        routing {
            post("/device/challenge") {
                call.respondText("""{"nonce":"${"n".repeat(43)}","purpose":"connect","audience":"http://localhost/device/connect","pairing_generation":1}""",
                    ContentType.Application.Json)
            }
            webSocket("/device/connect") {
                authorization = call.request.headers[HttpHeaders.Authorization]
                send(Frame.Text("""{"type":"hello","version":1,"session_id":"s1","session_generation":1,"pairing_generation":1}"""))
                launch { for (text in serverOut) send(Frame.Text(text)) }
                for (frame in incoming) if (frame is Frame.Text) seen.send(Json.parseToJsonElement(frame.readText()).jsonObject)
            }
        }
        val http = createClient { install(ClientWebSockets) }
        coroutineScope {
        val connected = CompletableDeferred<Unit>()
        val session = RelaySession(http, "http://localhost", 1, signer, manager, handler, { }, null, { connected.complete(Unit) })
        val running = async { session.run() }
        val register = withTimeout(5_000) { seen.receive() }
        assertTrue(authorization!!.startsWith("Bearer ") && authorization!!.split(".").size == 3)
        assertEquals("register_clients", register["type"]!!.jsonPrimitive.content)
        assertEquals(tokenFingerprint(token), register["clients"]!!.jsonArray.single().jsonObject["fingerprint"]!!.jsonPrimitive.content)
        assertFalse(connected.isCompleted)
        serverOut.send("""{"type":"ack","version":1,"request_id":"${register.getValue("request_id").jsonPrimitive.content}","ok":true}""")
        withTimeout(5_000) { connected.await() }

        fun command(id: String, operation: String, arguments: String) = """{"type":"command","version":1,"session_id":"s1","request_id":"$id",""" +
            """"expires_at":"${java.time.Instant.ofEpochMilli(System.currentTimeMillis() + 20_000)}","operation":"$operation","client_token":"$token","arguments":$arguments}"""
        serverOut.send(command("r1", "get_steps", """{"start_time":"2026-01-01T00:00:00Z","end_time":"2026-01-02T00:00:00Z"}"""))
        val result = withTimeout(5_000) { seen.receive() }
        assertEquals("r1", result["request_id"]!!.jsonPrimitive.content)
        assertEquals(42, result["body"]!!.jsonObject["total_steps"]!!.jsonPrimitive.int)

        serverOut.send(command("r1", "get_steps", """{"start_time":"2026-01-01T00:00:00Z","end_time":"2026-01-02T00:00:00Z"}"""))
        serverOut.send(command("r2", "delete_weight", """{"record_id":"owned"}"""))
        withTimeout(5_000) { while (approval.pending.value == null) delay(10) }
        assertTrue("A duplicate completed request must not produce a second result", seen.tryReceive().isFailure)
        serverOut.send("""{"type":"cancel","version":1,"session_id":"s1","request_id":"r2"}""")
        withTimeout(5_000) { while (approval.pending.value != null) delay(10) }

        serverOut.send(command("r3", "delete_weight", """{"record_id":"owned"}"""))
        withTimeout(5_000) { while (approval.pending.value == null) delay(10) }
        running.cancelAndJoin()
        assertNull("disconnect must withdraw the pending deletion", approval.pending.value)
        }
    }
}
