package dev.healthbridge.api

import dev.healthbridge.data.*
import dev.healthbridge.health.*
import dev.healthbridge.security.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

class PhoneLockedException : SecurityException("The phone is locked.")
class PowerSavingException : SecurityException("Battery saver is on.")
class RequestExpiredException : IllegalStateException("The request expired.")
val bridgeJson = Json { explicitNulls = false; encodeDefaults = true }
fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(mapOf(*fields))
fun text(value: String) = JsonPrimitive(value)

const val MAX_RESULT_BYTES = 65_536
const val MAX_RESULT_CHARS = 90_000
fun withinResultBudget(json: String) = json.length <= MAX_RESULT_CHARS && json.toByteArray(Charsets.UTF_8).size <= MAX_RESULT_BYTES
fun failure(category: String, message: String) =
    obj("success" to JsonPrimitive(false), "error_category" to text(category), "message" to text(message))

/** HTTP status for a structured category, mirrored by the backend. */
fun statusForCategory(category: String?): Int = when (category) {
    null -> 200
    "unauthorized" -> 401
    "permission_denied", "health_permission_denied", "phone_locked", "power_saving", "approval_required", "approval_denied" -> 403
    "approval_pending", "idempotency_conflict" -> 409
    "invalid_input" -> 400
    "not_found" -> 404
    "result_too_large" -> 422
    "rate_limited" -> 429
    "expired_request", "outcome_unknown" -> 504
    else -> 503
}

class HealthActions(
    private val health: HealthRepository,
    private val clients: ClientManager,
    private val audit: AuditSink,
    private val approval: PhoneApproval = PhoneApproval(),
    private val phoneUnlocked: () -> Boolean = { true },
    private val powerSaving: () -> Boolean = { false },
    private val running: () -> Boolean,
) {
    /**
     * Executes one authorized operation. Every phone-side gate (credential, client grant, lock, power saver,
     * Health Connect grant) is checked here independently of the backend's fingerprint check.
     * [deadlineMillis] is the wall-clock time after which the remote caller no longer waits.
     */
    suspend fun execute(token: String, operation: String, arguments: JsonObject, deadlineMillis: Long? = null,
                        auth: OAuthBinding? = null): JsonObject {
        require(operation in permissions) { "Unsupported operation." }
        var caller: ClientEntity? = null
        try {
            if (healthPermissions.containsKey(operation)) {
                val blocked = when {
                    !phoneUnlocked() -> "phone_locked" to "Unlock the phone to allow health access."
                    powerSaving() -> "power_saving" to "Battery saver is on. Turn it off to allow health access."
                    else -> null
                }
                if (blocked != null) {
                    caller = clients.identify(token, auth)
                    audit.record(caller, operation, "Denied", blocked.first)
                    return failure(blocked.first, blocked.second)
                }
            }
            if (operation.startsWith("delete_")) {
                val client = clients.identify(token, auth)
                caller = client
                if (!clients.permits(client, auth, permissions[operation])) throw ClientPermissionException()
                require(arguments.keys == setOf("record_id"))
                val id = arguments.getValue("record_id").jsonPrimitive.also { require(it.isString) }.content
                require(id.isNotBlank() && id.length <= 200)
                val remaining = deadlineMillis?.let { it - System.currentTimeMillis() - 1_000 } ?: PhoneApproval.MAX_WAIT_MILLIS
                if (remaining <= 0) throw ApprovalDeniedException()
                approval.request(client.name, operation.removePrefix("delete_"), id, minOf(remaining, PhoneApproval.MAX_WAIT_MILLIS))
            }
            var result = clients.execute(token, permissions[operation], auth) { client ->
                caller = client
                withContext(HealthOperationGuard { checkLive(deadlineMillis) }) {
                    perform(operation, arguments, client, deadlineMillis)
                }
            }
            if (healthPermissions.containsKey(operation) && (!phoneUnlocked() || powerSaving())) {
                result = if (operation.startsWith("get_")) {
                    if (!phoneUnlocked()) failure("phone_locked", "Unlock the phone to allow health access.")
                    else failure("power_saving", "Battery saver is on. Turn it off to allow health access.")
                } else obj("success" to JsonPrimitive(true))
            }
            if (!withinResultBudget(result.toString())) {
                audit.record(caller, operation, "Error", "result_too_large")
                return failure("result_too_large", "The result exceeds 64 KiB. Request a shorter date range.")
            }
            audit.record(caller, operation, if (result["error_category"] == null) "Success" else "Denied")
            return result
        } catch (e: CancellationException) { throw e }
        catch (e: AuthenticationException) { throw e }
        catch (e: Exception) {
            val (category, message) = when (e) {
                is ApprovalDeniedException -> "approval_denied" to "The deletion was not approved on the phone. No change was made."
                is ApprovalBusyException -> "approval_pending" to "Another deletion is awaiting confirmation on the phone."
                is ClientPermissionException -> "permission_denied" to "This application does not have permission. Change its access on the phone."
                is PhoneLockedException -> "phone_locked" to "Unlock the phone to allow health access."
                is PowerSavingException -> "power_saving" to "Battery saver is on. Turn it off to allow health access."
                is RequestExpiredException -> "expired_request" to "The request expired before a health operation started."
                is SecurityException -> "health_permission_denied" to "Health Connect permission is missing or revoked. Grant it on the phone."
                is IdempotencyConflictException -> "idempotency_conflict" to "This retry key was already used with different arguments."
                is OutcomeUnknownException -> "outcome_unknown" to "The write may or may not have completed. Retry with the same idempotency_key."
                is SerializationException, is IllegalArgumentException -> "invalid_input" to "Check the fields, timestamps, date range, measurements and idempotency_key."
                is IllegalStateException -> "health_unavailable" to "Health Connect is unavailable or returned an invalid result."
                else -> "health_api_error" to "The health operation failed. A successful write is not being reported."
            }
            if (caller == null) caller = clients.identify(token, auth)
            audit.record(caller, operation, if (e is SecurityException) "Denied" else "Error", category)
            return failure(category, message)
        }
    }

    private suspend fun checkLive(deadlineMillis: Long?) {
        currentCoroutineContext().ensureActive()
        if (deadlineMillis != null && System.currentTimeMillis() >= deadlineMillis) throw RequestExpiredException()
        if (!phoneUnlocked()) throw PhoneLockedException()
        if (powerSaving()) throw PowerSavingException()
    }

    private suspend fun perform(operation: String, args: JsonObject, client: ClientEntity, deadlineMillis: Long?): JsonObject {
        healthPermissions[operation]?.let {
            checkLive(deadlineMillis)
            check(health.available()) { "Health Connect is unavailable." }
            if (it !in health.grantedPermissions()) throw SecurityException("Health permission is not granted.")
            checkLive(deadlineMillis)
        }
        return when (operation) {
            "get_gateway_status" -> {
                require(args.isEmpty())
                obj("running" to JsonPrimitive(running()), "health_connect_available" to JsonPrimitive(health.available()),
                    "supported_data_types" to JsonArray(listOf("nutrition", "weight", "steps").map(::text)),
                    "gateway_version" to text("1.0.0"))
            }
            "get_permissions" -> {
                require(args.isEmpty())
                obj("client_id" to text(client.id), "permissions" to JsonArray(client.allowed().sortedBy { it.name }.map { text(it.name) }),
                    "health_connect_permissions" to JsonArray(health.grantedPermissions().sorted().map(::text)))
            }
            "get_nutrition", "get_weight", "get_steps" -> {
                require(args.keys == setOf("start_time", "end_time"))
                val start = args.getValue("start_time").jsonPrimitive.also { require(it.isString) }.content
                val end = args.getValue("end_time").jsonPrimitive.also { require(it.isString) }.content
                dateRange(start, end)
                when (operation) {
                    "get_nutrition" -> obj("records" to bridgeJson.encodeToJsonElement(health.nutrition(start, end)))
                    "get_weight" -> obj("records" to bridgeJson.encodeToJsonElement(health.weight(start, end)))
                    else -> obj("total_steps" to JsonPrimitive(health.steps(start, end)), "start_time" to text(start), "end_time" to text(end))
                }
            }
            "add_nutrition" -> {
                val entry = bridgeJson.decodeFromJsonElement<NutritionEntry>(args).also { it.validate() }
                val result = clients.writeOnce(client, operation, entry.idempotency_key,
                    bridgeJson.encodeToString(NutritionEntry.serializer(), entry.copy(idempotency_key = null)), entry.timestamp) {
                    checkLive(deadlineMillis)
                    nativeWrite { health.addNutrition(entry, it) }
                }
                JsonObject(bridgeJson.encodeToJsonElement(result).jsonObject +
                    ("recorded_values" to bridgeJson.encodeToJsonElement(entry.copy(idempotency_key = null))))
            }
            "add_weight" -> {
                val entry = bridgeJson.decodeFromJsonElement<WeightEntry>(args).also { it.validate() }
                val result = clients.writeOnce(client, operation, entry.idempotency_key,
                    bridgeJson.encodeToString(WeightEntry.serializer(), entry.copy(idempotency_key = null)), entry.timestamp) {
                    checkLive(deadlineMillis)
                    nativeWrite { health.addWeight(entry, it) }
                }
                JsonObject(bridgeJson.encodeToJsonElement(result).jsonObject +
                    ("recorded_values" to bridgeJson.encodeToJsonElement(entry.copy(idempotency_key = null))))
            }
            "delete_nutrition", "delete_weight" -> {
                require(args.keys == setOf("record_id"))
                val id = args.getValue("record_id").jsonPrimitive.also { require(it.isString) }.content
                if (operation == "delete_nutrition") health.deleteNutrition(id) else health.deleteWeight(id)
                obj("success" to JsonPrimitive(true), "deleted_record_id" to text(id),
                    "samsung_health_visibility" to text("not_verified"))
            }
            else -> error("Unsupported operation.")
        }
    }

    /** Once a native write starts, failures other than a definite permission error are reported as uncertain. */
    private suspend fun <T> nativeWrite(block: suspend () -> T): T = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: RequestExpiredException) { throw e }
        catch (e: SecurityException) { throw e }
        catch (e: Exception) { throw OutcomeUnknownException() }

    companion object {
        val permissions = mapOf(
            "get_gateway_status" to null, "get_permissions" to null,
            "get_nutrition" to ClientPermission.NUTRITION_READ, "add_nutrition" to ClientPermission.NUTRITION_WRITE,
            "get_weight" to ClientPermission.WEIGHT_READ, "add_weight" to ClientPermission.WEIGHT_WRITE,
            "get_steps" to ClientPermission.STEPS_READ, "delete_nutrition" to ClientPermission.NUTRITION_DELETE,
            "delete_weight" to ClientPermission.WEIGHT_DELETE,
        )
        private val healthPermissions = mapOf(
            "get_nutrition" to "android.permission.health.READ_NUTRITION",
            "add_nutrition" to "android.permission.health.WRITE_NUTRITION",
            "delete_nutrition" to "android.permission.health.WRITE_NUTRITION",
            "get_weight" to "android.permission.health.READ_WEIGHT",
            "add_weight" to "android.permission.health.WRITE_WEIGHT",
            "delete_weight" to "android.permission.health.WRITE_WEIGHT",
            "get_steps" to "android.permission.health.READ_STEPS",
        )
    }
}
