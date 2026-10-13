package dev.androidmcp.bridge.security

import dev.androidmcp.bridge.data.ClientEntity
import dev.androidmcp.bridge.data.ClientStore
import dev.androidmcp.bridge.data.WriteReceipt
import dev.androidmcp.bridge.health.WriteResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID

enum class ClientPermission(val label: String, val scope: String) {
    NUTRITION_READ("Nutrition read", "health.nutrition.read"), NUTRITION_WRITE("Nutrition write", "health.nutrition.write"),
    WEIGHT_READ("Weight read", "health.weight.read"), WEIGHT_WRITE("Weight write", "health.weight.write"), STEPS_READ("Steps read", "health.steps.read"),
    NUTRITION_DELETE("Delete own nutrition", "health.nutrition.delete"), WEIGHT_DELETE("Delete own weight", "health.weight.delete"),
    HYDRATION_READ("Water read", "health.hydration.read"), HYDRATION_WRITE("Water write", "health.hydration.write"),
    SLEEP_READ("Sleep read", "health.sleep.read"), SLEEP_WRITE("Sleep write", "health.sleep.write"),
    WORKOUTS_READ("Workouts read", "health.workouts.read"), WORKOUTS_WRITE("Workouts write", "health.workouts.write"),
    HEART_RATE_READ("Heart rate read", "health.heart_rate.read"), ACTIVE_CALORIES_READ("Active calories read", "health.active_calories.read"),
    BODY_READ("Body measurements read", "health.body.read"), BODY_WRITE("Body measurements write", "health.body.write"),
    SUMMARY_READ("Daily summary and data freshness", "health.summary.read"),
    OXYGEN_READ("Blood oxygen read", "health.oxygen.read"), OXYGEN_WRITE("Blood oxygen write", "health.oxygen.write"),
    VITALS_READ("HRV and respiratory rate read", "health.vitals.read"), ACTIVITY_READ("Activity detail read", "health.activity.read"),
    MINDFULNESS_READ("Mindfulness read", "health.mindfulness.read"), MINDFULNESS_WRITE("Mindfulness write", "health.mindfulness.write"),
    CLOCK_ALARM_READ("Alarms: see next, open list", "clock.alarm.read"), CLOCK_ALARM_SET("Alarms: set", "clock.alarm.set"),
    CLOCK_ALARM_DISMISS("Alarms: dismiss", "clock.alarm.dismiss"), CLOCK_TIMER_SET("Timers: start", "clock.timer.set"),
    NOTIFY_SEND("Notifications: show on phone", "notify.send"), NOTIFY_SPEAK("Speak aloud on phone", "notify.speak")
}
/** OAuth scope for status and permission checks; carries no health data. */
const val STATUS_SCOPE = "bridge.status"
val OAUTH_SCOPES = setOf(STATUS_SCOPE) + ClientPermission.entries.map { it.scope }
fun ClientEntity.scopeSet(): Set<String> = oauthScopes?.split(' ')?.filter { it in OAUTH_SCOPES }?.toSet() ?: emptySet()
val ClientEntity.linked get() = oauthClientId != null

/** Authorization context the instance attaches to OAuth (MCP) commands. The phone checks it against its own record. */
data class OAuthBinding(val oauthClientId: String, val clientId: String, val grantVersion: Int, val pairingGeneration: Int, val scopes: Set<String>)
/** Instance-side registration for OAuth-linked clients. */
data class OAuthRegistration(val scopes: Set<String>, val pairingGeneration: Int, val grantVersion: Int)
/** Grant created on this phone after the owner approved a link request; the secret goes to the instance once. */
data class LinkGrant(val client: ClientEntity, val secret: String)
fun ClientEntity.allowed() = ClientPermission.entries.filter { it.name in permissions.split(',') }.toSet()
fun Set<ClientPermission>.encode() = sortedBy { it.name }.joinToString(",") { it.name }
class AuthenticationException : SecurityException("Missing, invalid, disabled or revoked client credential.")
class ClientPermissionException : SecurityException("The client is not allowed to perform this operation.")
class OutcomeUnknownException : IllegalStateException("The write may or may not have completed.")
/** Public, non-secret registration sent to the paired instance: SHA-256 of the raw token and its expiry. */
data class RemoteRegistration(val clientId: String, val fingerprint: String, val expiresAt: Long, val oauth: OAuthRegistration? = null)
fun tokenFingerprint(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
class IdempotencyConflictException : IllegalArgumentException("This idempotency key was already used with different arguments.")

class ClientManager(private val store: ClientStore, val tokens: TokenManager, private val currentGeneration: suspend () -> Int? = { null }) {
    /** Invoked after every credential or permission change so the relay re-registers the full set immediately. */
    @Volatile var onCredentialsChanged: () -> Unit = {}
    companion object {
        const val TOKEN_LIFETIME_DAYS = 90
        const val MAX_CLIENTS = 20
        private const val TOKEN_LIFETIME_MILLIS = TOKEN_LIFETIME_DAYS * 86_400_000L
    }
    // Revocation and execution share a lock: once revocation returns, no old request can start a health operation.
    private val lock = Mutex()
    suspend fun create(name: String, permissions: Set<ClientPermission>): Pair<ClientEntity, String> = lock.withLock {
        require(name.isNotBlank() && name.length <= 80) { "Application name must contain 1-80 characters." }
        require(store.all().size < MAX_CLIENTS) { "At most $MAX_CLIENTS applications are supported. Delete an unused application first." }
        val id = UUID.randomUUID().toString()
        val token = tokens.generate(id)
        val client = ClientEntity(id, name.trim(), permissions = permissions.encode(), verifier = tokens.digest(token),
            expiresAt = System.currentTimeMillis() + TOKEN_LIFETIME_MILLIS, remoteFingerprint = tokenFingerprint(token))
        store.save(client)
        client to token
    }.also { onCredentialsChanged() }
    /** Enabled, unrevoked, unexpired clients. Disabled clients are omitted, so the backend rejects them before dispatch. */
    suspend fun registrations(now: Long = System.currentTimeMillis()): List<RemoteRegistration> = lock.withLock {
        val active = store.all().filter { it.enabled && it.verifier != null && it.expiresAt > now && it.remoteFingerprint != null }
        require(active.size <= MAX_CLIENTS) { "Too many applications for remote access. Delete unused applications." }
        active.sortedBy { it.id }.map {
            RemoteRegistration(it.id, it.remoteFingerprint!!, it.expiresAt,
                if (it.linked) OAuthRegistration(it.scopeSet(), it.pairingGeneration, it.grantVersion) else null)
        }
    }
    /**
     * Token check plus, for OAuth-linked clients, an exact match of the instance-supplied binding against this phone's
     * record and current pairing. Token clients must not carry a binding; linked clients must.
     */
    private suspend fun authenticate(token: String, auth: OAuthBinding?): ClientEntity {
        val id = tokens.clientId(token) ?: throw AuthenticationException()
        val client = store.find(id) ?: throw AuthenticationException()
        val verifier = client.verifier ?: throw AuthenticationException()
        if (!client.enabled || client.expiresAt <= System.currentTimeMillis() || !tokens.matches(token, verifier)) throw AuthenticationException()
        if (!client.linked) { if (auth != null) throw AuthenticationException(); return client }
        val generation = currentGeneration()
        if (auth == null || auth.oauthClientId != client.oauthClientId || auth.clientId != client.id || auth.grantVersion != client.grantVersion ||
            auth.pairingGeneration != client.pairingGeneration || generation != client.pairingGeneration ||
            !client.scopeSet().containsAll(auth.scopes)) throw AuthenticationException()
        return client
    }
    /** Phone-side grant check. Linked clients need the scope in both the phone record and the presented token. */
    fun permits(client: ClientEntity, auth: OAuthBinding?, permission: ClientPermission?): Boolean {
        if (client.linked) {
            val scope = permission?.scope ?: STATUS_SCOPE
            if (auth == null || scope !in auth.scopes || scope !in client.scopeSet()) return false
        }
        return permission == null || permission in client.allowed()
    }
    suspend fun identify(token: String, auth: OAuthBinding? = null): ClientEntity = lock.withLock { authenticate(token, auth) }
    suspend fun <T> execute(token: String, permission: ClientPermission?, auth: OAuthBinding? = null,
                            operation: suspend (ClientEntity) -> T): T = lock.withLock {
        val client = authenticate(token, auth)
        if (!permits(client, auth, permission)) throw ClientPermissionException()
        val result = operation(client)
        store.save(client.copy(lastAccessedAt = System.currentTimeMillis()))
        result
    }
    /**
     * Creates the phone-side grant for an approved OAuth link. [scopes] must be a subset of the request. The returned
     * secret is sent once to the instance, which keeps only its hash and an encrypted copy inside OAuth token props.
     */
    suspend fun createOAuthLink(oauthClientId: String, scopes: Set<String>, pairingGeneration: Int, name: String = "ChatGPT (MCP)"): LinkGrant =
        lock.withLock {
            require(scopes.isNotEmpty() && OAUTH_SCOPES.containsAll(scopes)) { "Select at least one permission." }
            require(store.all().size < MAX_CLIENTS) { "At most $MAX_CLIENTS applications are supported. Delete an unused application first." }
            val id = UUID.randomUUID().toString()
            val token = tokens.generate(id)
            val permissions = ClientPermission.entries.filter { it.scope in scopes }.toSet()
            val client = ClientEntity(id, name, permissions = permissions.encode(), verifier = tokens.digest(token),
                expiresAt = System.currentTimeMillis() + TOKEN_LIFETIME_MILLIS, remoteFingerprint = tokenFingerprint(token),
                oauthClientId = oauthClientId, oauthScopes = scopes.sorted().joinToString(" "), grantVersion = 1,
                pairingGeneration = pairingGeneration)
            store.save(client)
            LinkGrant(client, token)
        }
    suspend fun update(id: String, name: String, enabled: Boolean, permissions: Set<ClientPermission>) = lock.withLock {
        require(name.isNotBlank() && name.length <= 80) { "Application name must contain 1-80 characters." }
        val client = requireNotNull(store.find(id)) { "Client no longer exists." }
        // A linked client can only be narrowed on the phone; widening needs a new OAuth approval.
        if (client.linked) require(permissions.all { it.scope in client.scopeSet() }) { "Reconnect in ChatGPT to grant more access." }
        store.save(client.copy(name = name.trim(), enabled = enabled, permissions = permissions.encode()))
    }.also { onCredentialsChanged() }
    suspend fun revoke(id: String) = lock.withLock {
        val client = requireNotNull(store.find(id)) { "Client no longer exists." }
        store.save(client.copy(verifier = null, remoteFingerprint = null))
    }.also { onCredentialsChanged() }
    suspend fun regenerate(id: String): String = lock.withLock {
        val client = requireNotNull(store.find(id)) { "Client no longer exists." }
        require(!client.linked) { "Linked clients are renewed by reconnecting in ChatGPT." }
        val token = tokens.generate(id)
        store.save(client.copy(verifier = tokens.digest(token), expiresAt = System.currentTimeMillis() + TOKEN_LIFETIME_MILLIS,
            remoteFingerprint = tokenFingerprint(token)))
        token
    }.also { onCredentialsChanged() }
    suspend fun delete(id: String) = lock.withLock { store.delete(id) }.also { onCredentialsChanged() }

    // Called only inside execute. A pending receipt survives process death; Health Connect's client ID handles the retry.
    suspend fun writeOnce(client: ClientEntity, operation: String, key: String?, payload: String, timestamp: String,
                          write: suspend (String) -> WriteResult): WriteResult {
        require(key != null && key.isNotBlank() && key.length <= 128) { "idempotency_key is required for writes." }
        val keyHash = tokens.digest("key:${client.id}:$operation:$key")
        val payloadHash = tokens.digest("payload:$payload")
        val prior = store.receipt(client.id, operation, keyHash)
        if (prior != null && prior.payloadHash != payloadHash) throw IdempotencyConflictException()
        if (prior?.recordId != null) return WriteResult(record_id = prior.recordId, timestamp = timestamp)
        val receipt = prior ?: WriteReceipt(client.id, operation, keyHash, payloadHash, "androidmcp:${client.id}:$keyHash")
        store.saveReceipt(receipt)
        val result = write(receipt.recordKey)
        store.saveReceipt(receipt.copy(recordId = result.record_id))
        return result
    }
}
