package dev.androidmcp.bridge.relay

import dev.androidmcp.bridge.api.HealthActions
import dev.androidmcp.bridge.api.failure
import dev.androidmcp.bridge.security.AuthenticationException
import dev.androidmcp.bridge.security.ClientManager
import dev.androidmcp.bridge.security.LinkApproval
import dev.androidmcp.bridge.security.LinkDecision
import dev.androidmcp.bridge.security.LinkRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.json.*
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

fun relayHttpClient() = HttpClient(CIO) {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) { connectTimeoutMillis = 15_000; requestTimeoutMillis = 20_000 }
    HttpResponseValidator {
        validateResponse { response ->
            if (response.call.request.url.encodedPath == "/device/connect" && response.status.value != 101) {
                throw RelayException(response.status.value, "Device authentication failed (HTTP ${response.status.value}).",
                    fatal = response.status.value in setOf(401, 403, 409))
            }
        }
    }
    install(WebSockets) { maxFrameSize = 262_144 }
}

/** Accepts only a canonical HTTPS origin, with no path, query, credentials or fragment. */
fun canonicalOrigin(input: String): String? = runCatching {
    val uri = URI(input.trim().removeSuffix("/"))
    val host = uri.host?.lowercase() ?: return null
    if (uri.scheme != "https" || uri.userInfo != null || !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) return null
    "${uri.scheme}://$host" + if (uri.port == -1) "" else ":${uri.port}"
}.getOrNull()

class RelayException(val status: Int?, message: String, val fatal: Boolean = false) : Exception(message)

data class PairingResult(val pairingGeneration: Int, val deviceKid: String)

/** One-time pairing. The bootstrap secret is used for two requests and never stored, logged or reused. */
suspend fun pairDevice(http: HttpClient, origin: String, bootstrap: CharArray, signer: DeviceSigner): PairingResult {
    val bearer = "Bearer " + String(bootstrap)
    val challengeResponse = http.post("$origin/device/pair/challenge") { header(HttpHeaders.Authorization, bearer) }
    val challengeBody = challengeResponse.bodyAsText()
    if (challengeResponse.status.value != 200) throw pairingError(challengeResponse.status.value, challengeBody)
    val challenge = Json.parseToJsonElement(challengeBody).jsonObject
    require(challenge["audience"]?.jsonPrimitive?.content == origin) { "The instance reported a different audience." }
    val nonce = challenge.getValue("challenge").jsonPrimitive.content
    val proof = DeviceJws.pairingProof(signer, origin, nonce, System.currentTimeMillis() / 1000)
    val complete = http.post("$origin/device/pair/complete") {
        header(HttpHeaders.Authorization, bearer)
        contentType(ContentType.Application.Json)
        setBody(JsonObject(mapOf("proof" to JsonPrimitive(proof))).toString())
    }
    val completeBody = complete.bodyAsText()
    if (complete.status.value != 200) throw pairingError(complete.status.value, completeBody)
    val paired = Json.parseToJsonElement(completeBody).jsonObject
    val kid = paired.getValue("device_kid").jsonPrimitive.content
    check(kid == DeviceJws.thumbprint(signer.publicKey)) { "The instance pinned a different device key." }
    return PairingResult(paired.getValue("pairing_generation").jsonPrimitive.int, kid)
}

private fun pairingError(status: Int, body: String): RelayException {
    val category = runCatching { Json.parseToJsonElement(body).jsonObject["error_category"]?.jsonPrimitive?.content }.getOrNull()
    val message = when (category) {
        "unauthorized" -> "The bootstrap secret was rejected or has already been used."
        "already_paired" -> "This instance already has a paired phone. Reset pairing in the private instance configuration first."
        "not_configured" -> "The instance has no owner bootstrap secret configured."
        "rate_limited" -> "Too many attempts. Wait a minute and try again."
        else -> "Pairing failed (HTTP $status)."
    }
    return RelayException(status, message, fatal = true)
}

/**
 * One authenticated session. Returns when the socket closes; cancelling the caller closes the socket and cancels every
 * in-flight command, which also withdraws any pending delete approval on the phone.
 */
class RelaySession(
    private val http: HttpClient,
    private val origin: String,
    private val pairingGeneration: Int,
    private val signer: DeviceSigner,
    private val clients: ClientManager,
    private val actions: HealthActions,
    private val onEvents: suspend (List<JsonObject>) -> Unit,
    private val linkApproval: LinkApproval? = null,
    private val onConnected: () -> Unit,
) {
    private val registrations = Channel<Unit>(Channel.CONFLATED)
    fun credentialsChanged() { registrations.trySend(Unit) }

    suspend fun run(): CloseReason? {
        val challengeResponse = http.post("$origin/device/challenge")
        val body = challengeResponse.bodyAsText()
        when (challengeResponse.status.value) {
            200 -> Unit
            409 -> throw RelayException(409, "The instance is not paired with this phone. Pair again.", fatal = true)
            else -> throw RelayException(challengeResponse.status.value, "Challenge failed (HTTP ${challengeResponse.status.value}).")
        }
        val challenge = Json.parseToJsonElement(body).jsonObject
        if (challenge["audience"]?.jsonPrimitive?.content != "$origin/device/connect")
            throw RelayException(null, "The instance reported a different audience.", fatal = true)
        if (challenge["pairing_generation"]?.jsonPrimitive?.intOrNull != pairingGeneration)
            throw RelayException(null, "The instance pairing was reset. Pair this phone again.", fatal = true)
        val proof = DeviceJws.connectProof(signer, origin, challenge.getValue("nonce").jsonPrimitive.content,
            pairingGeneration, System.currentTimeMillis() / 1000)
        val wsUrl = origin.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/device/connect"
        val session = try {
            http.webSocketSession(wsUrl) { header(HttpHeaders.Authorization, "Bearer $proof") }
        } catch (e: CancellationException) { throw e }
        catch (e: RelayException) { throw e }
        catch (e: Exception) {
            throw RelayException(null, "Connection failed (${e.javaClass.simpleName}).")
        }
        try { return serve(session) } finally { withContext(NonCancellable) { runCatching { session.close() } } }
    }

    private suspend fun serve(session: DefaultClientWebSocketSession): CloseReason? = coroutineScope {
        val hello = withTimeout(10_000) {
            val frame = session.incoming.receive() as? Frame.Text ?: throw RelayException(null, "Unexpected first frame.")
            RelayProtocol.parse(frame.readText())?.takeIf { RelayProtocol.type(it) == "hello" }
                ?: throw RelayException(null, "Missing session hello.")
        }
        val sessionId = hello["session_id"]?.jsonPrimitive?.contentOrNull ?: throw RelayException(null, "Invalid hello.")
        if (!sessionId.matches(Regex("[A-Za-z0-9_-]{1,64}")) ||
            hello["pairing_generation"]?.jsonPrimitive?.intOrNull != pairingGeneration) {
            throw RelayException(null, "Invalid session identity.", fatal = true)
        }
        val outgoing = Channel<String>(64)
        val jobs = ConcurrentHashMap<String, Job>()
        val recentIds = mutableMapOf<String, Long>()
        val registrationIds = ConcurrentHashMap.newKeySet<String>()
        val registered = CompletableDeferred<Unit>()
        var linkJob: Job? = null
        launch { outgoing.consumeEach { session.send(Frame.Text(it)) } }
        launch { while (isActive) { delay(120_000); outgoing.send("ping") } }
        launch {
            registrations.trySend(Unit)
            for (signal in registrations) {
                val id = UUID.randomUUID().toString()
                registrationIds.add(id)
                outgoing.send(RelayProtocol.registerClients(id, clients.registrations()))
            }
        }
        launch { withTimeout(10_000) { registered.await() }; onConnected() }
        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                if (text == "pong") continue
                val message = RelayProtocol.parse(text) ?: continue
                when (RelayProtocol.type(message)) {
                    "command" -> {
                        if (!registered.isCompleted) throw RelayException(null, "Command arrived before credential registration.")
                        val (requestId, parsed) = RelayProtocol.command(message, sessionId, System.currentTimeMillis())
                        val command = parsed.getOrNull()
                        if (command == null) {
                            val category = (parsed.exceptionOrNull() as? RelayRejection)?.category ?: "protocol_error"
                            if (requestId != null) outgoing.send(RelayProtocol.result(sessionId, requestId,
                                failure(category, "The command was rejected by the phone.")))
                            continue
                        }
                        recentIds.entries.removeAll { it.value <= System.currentTimeMillis() }
                        if (recentIds.containsKey(command.requestId)) continue
                        if (jobs.size >= 4) {
                            outgoing.send(RelayProtocol.result(sessionId, command.requestId, failure("rate_limited", "Too many in-flight requests.")))
                            continue
                        }
                        if (recentIds.size >= 240) throw RelayException(null, "Too many recent command identifiers.")
                        recentIds[command.requestId] = command.expiresAt
                        val job = launch(start = CoroutineStart.LAZY) {
                            try { outgoing.send(RelayProtocol.result(sessionId, command.requestId, execute(command))) }
                            finally { jobs.remove(command.requestId) }
                        }
                        jobs[command.requestId] = job
                        job.start()
                    }
                    "cancel" -> if (message["session_id"]?.jsonPrimitive?.contentOrNull == sessionId)
                        message["request_id"]?.jsonPrimitive?.contentOrNull?.let { jobs[it]?.cancel() }
                    "link_request" -> {
                        val request = RelayProtocol.linkRequest(message, System.currentTimeMillis())
                        if (request == null || linkApproval == null || linkJob?.isActive == true) {
                            request?.let { outgoing.send(RelayProtocol.linkDenied(it)) }
                            continue
                        }
                        linkJob = launch { outgoing.send(decideLink(request)) }
                    }
                    "link_ack" -> {
                        // A rejected approval leaves no usable phone grant behind.
                        val pending = linkedClient
                        linkedClient = null
                        android.util.Log.i("HB", "link_ack ok=${message["ok"]} error=${message["error"]}")
                        if (pending != null && message["ok"]?.jsonPrimitive?.booleanOrNull != true) runCatching { clients.delete(pending) }
                    }
                    "security_events" -> {
                        val events = RelayProtocol.securityEvents(message)
                        onEvents(events)
                        events.mapNotNull { it["seq"]?.jsonPrimitive?.longOrNull }.maxOrNull()?.let { outgoing.send(RelayProtocol.eventsAck(it)) }
                    }
                    "ack" -> {
                        val id = message["request_id"]?.jsonPrimitive?.contentOrNull
                        if (id == null || !registrationIds.remove(id)) throw RelayException(null, "Unexpected registration acknowledgement.")
                        if (message["ok"]?.jsonPrimitive?.booleanOrNull != true)
                            throw RelayException(null, "The instance rejected the client registration.", fatal = true)
                        registered.complete(Unit)
                    }
                    else -> Unit
                }
            }
            session.closeReason.await()
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @Volatile private var linkedClient: String? = null

    /** Owner decision on this phone (biometric/PIN in the UI). Only on approval is a phone-side grant created. */
    private suspend fun decideLink(request: LinkRequest): String {
        android.util.Log.i("HB", "link request ${request.linkId} shown")
        val decision = linkApproval?.request(request) ?: LinkDecision(false)
        android.util.Log.i("HB", "link decision approved=${decision.approved} scopes=${decision.scopes.size} expired=${System.currentTimeMillis() >= request.expiresAt}")
        if (!decision.approved || System.currentTimeMillis() >= request.expiresAt) return RelayProtocol.linkDenied(request)
        val grant = runCatching { clients.createOAuthLink(request.oauthClientId, decision.scopes, pairingGeneration) }
            .onFailure { android.util.Log.e("HB", "createOAuthLink failed", it) }.getOrNull()
            ?: return RelayProtocol.linkDenied(request)
        linkedClient = grant.client.id
        return RelayProtocol.linkApproved(request, grant)
    }

    private suspend fun execute(command: RelayCommand): JsonObject {
        val remaining = command.expiresAt - System.currentTimeMillis()
        return try {
            withTimeout(remaining.coerceAtLeast(1)) {
                actions.execute(command.clientToken, command.operation, command.arguments, command.expiresAt, command.auth)
            }
        } catch (e: TimeoutCancellationException) {
            if (command.mutating) failure("outcome_unknown", "The phone did not finish before the deadline. Retry with the same idempotency_key.")
            else failure("expired_request", "The request expired before the phone finished.")
        } catch (e: AuthenticationException) {
            failure("unauthorized", "The client credential is invalid, disabled, expired or revoked.")
        }
    }
}
