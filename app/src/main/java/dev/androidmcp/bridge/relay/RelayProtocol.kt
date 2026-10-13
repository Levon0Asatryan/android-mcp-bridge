package dev.androidmcp.bridge.relay

import dev.androidmcp.bridge.api.HealthActions
import dev.androidmcp.bridge.api.failure
import dev.androidmcp.bridge.api.withinResultBudget
import dev.androidmcp.bridge.security.LinkGrant
import dev.androidmcp.bridge.security.LinkRequest
import dev.androidmcp.bridge.security.OAUTH_SCOPES
import dev.androidmcp.bridge.security.OAuthBinding
import kotlinx.serialization.json.*
import java.time.Instant

const val RELAY_PROTOCOL_VERSION = 1

data class RelayCommand(val requestId: String, val expiresAt: Long, val operation: String, val clientToken: String,
                        val arguments: JsonObject, val auth: OAuthBinding? = null) {
    val mutating get() = operation.startsWith("add_") || operation.startsWith("update_") || operation.startsWith("delete_")
}

/** Strict validation of backend messages. Anything unexpected is rejected rather than interpreted. */
object RelayProtocol {
    private val commandKeys = setOf("type", "version", "session_id", "request_id", "expires_at", "operation", "client_token", "arguments")
    private val requestIdPattern = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val uuidPattern = Regex("^[0-9a-f-]{36}$")
    private val oauthClientPattern = Regex("^[A-Za-z0-9._~-]{8,128}$")

    fun parse(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?.takeIf { (it["version"] as? JsonPrimitive)?.intOrNull == RELAY_PROTOCOL_VERSION && (it["type"] as? JsonPrimitive)?.isString == true }

    fun type(message: JsonObject) = message.getValue("type").jsonPrimitive.content
    private val eventTypes = setOf("device_connected", "client_first_use", "auth_failures", "challenge_flood",
        "device_replacement_rejected", "pairing_reset", "device_paired", "client_linked")

    fun securityEvents(message: JsonObject): List<JsonObject> {
        val entries = message["events"] as? JsonArray ?: throw RelayException(null, "Invalid security events.")
        require(entries.size <= 100) { "Too many security events." }
        return entries.map {
            val event = it as? JsonObject ?: throw RelayException(null, "Invalid security event.")
            require(event.keys == setOf("seq", "at", "type", "detail", "count"))
            require((event["seq"] as? JsonPrimitive)?.longOrNull?.let { n -> n > 0 } == true)
            require((event["at"] as? JsonPrimitive)?.longOrNull?.let { n -> n >= 0 && n <= System.currentTimeMillis() + 60_000 } == true)
            require(event.string("type") in eventTypes)
            require((event["count"] as? JsonPrimitive)?.intOrNull?.let { n -> n > 0 } == true)
            val detail = event.string("detail")
            require(event["detail"] == JsonNull || detail in setOf("replaced_previous", "pairing", "pairing_proof", "device", "client", "oauth") ||
                detail?.matches(Regex("[0-9a-f-]{36}")) == true)
            event
        }.also { events ->
            val seq = events.map { it.getValue("seq").jsonPrimitive.long }
            require(seq.zipWithNext().all { (a, b) -> a < b })
        }
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Returns the request id when it is safe to answer, plus either a valid command or a rejection category. */
    fun command(message: JsonObject, sessionId: String, now: Long): Pair<String?, Result<RelayCommand>> {
        val requestId = message.string("request_id")?.takeIf(requestIdPattern::matches)
        fun reject(category: String) = requestId to Result.failure<RelayCommand>(RelayRejection(category))
        if ((message.keys != commandKeys && message.keys != commandKeys + "auth") || requestId == null) return reject("protocol_error")
        if (message.string("session_id") != sessionId) return null to Result.failure(RelayRejection("protocol_error"))
        val expires = message.string("expires_at")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: return reject("protocol_error")
        if (expires <= now) return reject("expired_request")
        if (expires - now > 30_000) return reject("protocol_error")
        val operation = message.string("operation")?.takeIf { it in HealthActions.permissions } ?: return reject("protocol_error")
        val token = message.string("client_token")?.takeIf { it.length in 1..256 } ?: return reject("unauthorized")
        val arguments = message["arguments"] as? JsonObject ?: return reject("invalid_input")
        if (arguments.toString().toByteArray(Charsets.UTF_8).size > 65_536) return reject("invalid_input")
        val auth = if ("auth" in message) (oauthBinding(message["auth"]) ?: return reject("unauthorized")) else null
        return requestId to Result.success(RelayCommand(requestId, expires, operation, token, arguments, auth))
    }

    private fun oauthBinding(value: JsonElement?): OAuthBinding? = runCatching {
        val auth = value as JsonObject
        require(auth.keys == setOf("kind", "oauth_client_id", "client_id", "grant_version", "pairing_generation", "scopes"))
        require(auth.string("kind") == "oauth")
        val scopes = (auth.getValue("scopes") as JsonArray).map { (it as JsonPrimitive).also { p -> require(p.isString) }.content }
        require(scopes.size <= OAUTH_SCOPES.size && OAUTH_SCOPES.containsAll(scopes))
        OAuthBinding(auth.string("oauth_client_id")!!.also { require(oauthClientPattern.matches(it)) },
            auth.string("client_id")!!.also { require(uuidPattern.matches(it)) },
            auth.getValue("grant_version").jsonPrimitive.int, auth.getValue("pairing_generation").jsonPrimitive.int, scopes.toSet())
    }.getOrNull()

    /** A browser authorization awaiting the owner's decision. Rejected unless every field is well formed and unexpired. */
    fun linkRequest(message: JsonObject, now: Long): LinkRequest? = runCatching {
        require(message.keys == setOf("type", "version", "link_id", "code", "binding", "oauth_client_id", "redirect_host", "scopes", "expires_at"))
        val scopes = (message.getValue("scopes") as JsonArray).map { (it as JsonPrimitive).also { p -> require(p.isString) }.content }.toSet()
        require(scopes.isNotEmpty() && OAUTH_SCOPES.containsAll(scopes))
        val expires = message.getValue("expires_at").jsonPrimitive.long
        require(expires > now && expires - now <= 130_000)
        LinkRequest(message.string("link_id")!!.also { require(uuidPattern.matches(it)) },
            message.string("code")!!.also { require(it.matches(Regex("^[0-9]{6}$"))) },
            message.string("binding")!!.also { require(it.matches(Regex("^[0-9a-f]{64}$"))) },
            message.string("oauth_client_id")!!.also { require(oauthClientPattern.matches(it)) },
            message.string("redirect_host")!!.also { require(it.length in 1..253 && it.matches(Regex("^[A-Za-z0-9.:-]+$"))) },
            scopes, expires)
    }.getOrNull()

    fun linkDenied(request: LinkRequest) = envelope("link_result", "link_id" to JsonPrimitive(request.linkId),
        "binding" to JsonPrimitive(request.binding), "approved" to JsonPrimitive(false))

    fun linkApproved(request: LinkRequest, grant: LinkGrant) = envelope("link_result", "link_id" to JsonPrimitive(request.linkId),
        "binding" to JsonPrimitive(request.binding), "approved" to JsonPrimitive(true),
        "scopes" to JsonArray(grant.client.oauthScopes.orEmpty().split(' ').map(::JsonPrimitive)),
        "client_id" to JsonPrimitive(grant.client.id), "grant_secret" to JsonPrimitive(grant.secret),
        "fingerprint" to JsonPrimitive(grant.client.remoteFingerprint), "expires_at" to JsonPrimitive(grant.client.expiresAt),
        "grant_version" to JsonPrimitive(grant.client.grantVersion), "pairing_generation" to JsonPrimitive(grant.client.pairingGeneration))

    /** Result envelope; bodies above 64 KiB or 90,000 characters become an explicit result_too_large failure. */
    fun result(sessionId: String, requestId: String, body: JsonObject): String {
        val safe = if (withinResultBudget(body.toString())) body
            else failure("result_too_large", "The result exceeds 64 KiB. Request a shorter date range.")
        return envelope("result", "session_id" to JsonPrimitive(sessionId), "request_id" to JsonPrimitive(requestId), "body" to safe)
    }

    fun registerClients(requestId: String, clients: List<dev.androidmcp.bridge.security.RemoteRegistration>) = envelope("register_clients",
        "request_id" to JsonPrimitive(requestId),
        "clients" to JsonArray(clients.map {
            val fields = mutableMapOf<String, JsonElement>("client_id" to JsonPrimitive(it.clientId),
                "fingerprint" to JsonPrimitive(it.fingerprint), "expires_at" to JsonPrimitive(it.expiresAt))
            it.oauth?.let { oauth ->
                fields["oauth"] = JsonObject(mapOf("client_id" to JsonPrimitive(it.clientId),
                    "scopes" to JsonArray(oauth.scopes.sorted().map(::JsonPrimitive)),
                    "pairing_generation" to JsonPrimitive(oauth.pairingGeneration), "grant_version" to JsonPrimitive(oauth.grantVersion)))
            }
            JsonObject(fields)
        }))

    fun eventsAck(upTo: Long) = envelope("events_ack", "up_to" to JsonPrimitive(upTo))

    private fun envelope(type: String, vararg fields: Pair<String, JsonElement>) =
        JsonObject(mapOf("type" to JsonPrimitive(type), "version" to JsonPrimitive(RELAY_PROTOCOL_VERSION)) + fields).toString()
}

class RelayRejection(val category: String) : Exception(category)
