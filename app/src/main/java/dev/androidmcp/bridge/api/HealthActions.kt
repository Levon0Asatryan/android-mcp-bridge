package dev.androidmcp.bridge.api

import dev.androidmcp.bridge.data.*
import dev.androidmcp.bridge.health.*
import dev.androidmcp.bridge.security.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

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
    private val modules: List<ActionModule> = emptyList(),
    private val running: () -> Boolean,
) : BridgeModule {
    override val id = "health"
    override val title = "Health"
    override val operations get() = healthOperations
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
            if (operation in gated) {
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
                    perform(operation, arguments, client, deadlineMillis, auth)
                }
            }
            if (operation in gated && (!phoneUnlocked() || powerSaving())) {
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
                is ModuleException -> e.category to (e.message ?: "The operation is unavailable.")
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

    private suspend fun perform(operation: String, args: JsonObject, client: ClientEntity, deadlineMillis: Long?,
                                auth: OAuthBinding?): JsonObject {
        modules.firstOrNull { operation in it.operations }?.let { return performModule(it, operation, args, client, deadlineMillis) }
        if (operation in gated) {
            checkLive(deadlineMillis)
            check(health.available()) { "Health Connect is unavailable." }
            healthPermissions[operation]?.let { needed ->
                if (needed.none { it in health.grantedPermissions() }) throw SecurityException("Health permission is not granted.")
            }
            checkLive(deadlineMillis)
        }
        return when (operation) {
            "get_gateway_status" -> {
                require(args.isEmpty())
                obj("running" to JsonPrimitive(running()), "health_connect_available" to JsonPrimitive(health.available()),
                    "supported_data_types" to JsonArray(DATA_TYPES.map(::text)),
                    "gateway_version" to text("1.0.0"))
            }
            "get_permissions" -> {
                require(args.isEmpty())
                obj("client_id" to text(client.id), "permissions" to JsonArray(client.allowed().sortedBy { it.name }.map { text(it.name) }),
                    "health_connect_permissions" to JsonArray(health.grantedPermissions().sorted().map(::text)))
            }
            "get_nutrition", "get_weight", "get_steps", "get_hydration", "get_sleep", "get_workouts", "get_heart_rate",
            "get_active_calories", "get_body_measurements", "get_oxygen_saturation", "get_vitals", "get_activity_detail",
            "get_mindfulness" -> {
                require(args.keys == setOf("start_time", "end_time"))
                val start = args.getValue("start_time").jsonPrimitive.also { require(it.isString) }.content
                val end = args.getValue("end_time").jsonPrimitive.also { require(it.isString) }.content
                dateRange(start, end)
                when (operation) {
                    "get_nutrition" -> obj("records" to bridgeJson.encodeToJsonElement(health.nutrition(start, end)))
                    "get_weight" -> obj("records" to bridgeJson.encodeToJsonElement(health.weight(start, end)))
                    "get_hydration" -> health.hydration(start, end)
                    "get_sleep" -> health.sleep(start, end)
                    "get_workouts" -> health.workouts(start, end)
                    "get_heart_rate" -> health.heartRate(start, end)
                    "get_active_calories" -> health.activeCalories(start, end)
                    "get_body_measurements" -> health.bodyMeasurements(start, end)
                    "get_oxygen_saturation" -> health.oxygenSaturation(start, end)
                    "get_vitals" -> health.vitals(start, end)
                    "get_activity_detail" -> health.activityDetail(start, end)
                    "get_mindfulness" -> health.mindfulness(start, end)
                    else -> obj("total_steps" to JsonPrimitive(health.steps(start, end)), "start_time" to text(start), "end_time" to text(end))
                }
            }
            "add_nutrition" -> {
                val entry = decodeNutrition(args).also { it.validate() }
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
            "add_hydration" -> {
                val entry = bridgeJson.decodeFromJsonElement<HydrationEntry>(args).also { it.validate() }
                writeResult(clients.writeOnce(client, operation, entry.idempotency_key,
                    bridgeJson.encodeToString(HydrationEntry.serializer(), entry.copy(idempotency_key = null)), entry.timestamp) {
                    checkLive(deadlineMillis)
                    nativeWrite { health.addHydration(entry, it) }
                }, bridgeJson.encodeToJsonElement(entry.copy(idempotency_key = null)))
            }
            "add_sleep" -> {
                val entry = bridgeJson.decodeFromJsonElement<SleepEntry>(args).also { it.validate() }
                writeResult(clients.writeOnce(client, operation, entry.idempotency_key,
                    bridgeJson.encodeToString(SleepEntry.serializer(), entry.copy(idempotency_key = null)), entry.start_time) {
                    checkLive(deadlineMillis)
                    nativeWrite { health.addSleep(entry, it) }
                }, bridgeJson.encodeToJsonElement(entry.copy(idempotency_key = null)))
            }
            "add_workout" -> {
                val entry = bridgeJson.decodeFromJsonElement<WorkoutEntry>(args).also { it.validate() }
                writeResult(clients.writeOnce(client, operation, entry.idempotency_key,
                    bridgeJson.encodeToString(WorkoutEntry.serializer(), entry.copy(idempotency_key = null)), entry.start_time) {
                    checkLive(deadlineMillis)
                    nativeWrite { health.addWorkout(entry, it) }
                }, bridgeJson.encodeToJsonElement(entry.copy(idempotency_key = null)))
            }
            "add_body_measurement" -> {
                val entry = bridgeJson.decodeFromJsonElement<BodyMeasurementEntry>(args).also { it.validate() }
                writeResult(clients.writeOnce(client, operation, entry.idempotency_key,
                    bridgeJson.encodeToString(BodyMeasurementEntry.serializer(), entry.copy(idempotency_key = null)), entry.timestamp) {
                    checkLive(deadlineMillis)
                    nativeWrite { health.addBodyMeasurement(entry, it) }
                }, bridgeJson.encodeToJsonElement(entry.copy(idempotency_key = null)))
            }
            "update_nutrition" -> {
                require(args.keys == setOf("record_id", "changes"))
                val id = args.getValue("record_id").jsonPrimitive.also { require(it.isString) }.content
                require(id.isNotBlank() && id.length <= 200)
                val changes = decodeChanges(args.getValue("changes").jsonObject)
                require(!changes.isEmpty())
                checkLive(deadlineMillis)
                val result = health.updateNutrition(id, changes)
                JsonObject(bridgeJson.encodeToJsonElement(result).jsonObject + ("updated_fields" to bridgeJson.encodeToJsonElement(changes)))
            }
            "add_oxygen_saturation" -> {
                val entry = bridgeJson.decodeFromJsonElement<OxygenEntry>(args).also { it.validate() }
                writeResult(clients.writeOnce(client, operation, entry.idempotency_key,
                    bridgeJson.encodeToString(OxygenEntry.serializer(), entry.copy(idempotency_key = null)), entry.timestamp) {
                    checkLive(deadlineMillis)
                    nativeWrite { health.addOxygenSaturation(entry, it) }
                }, bridgeJson.encodeToJsonElement(entry.copy(idempotency_key = null)))
            }
            "add_mindfulness" -> {
                val entry = bridgeJson.decodeFromJsonElement<MindfulnessEntry>(args).also { it.validate() }
                writeResult(clients.writeOnce(client, operation, entry.idempotency_key,
                    bridgeJson.encodeToString(MindfulnessEntry.serializer(), entry.copy(idempotency_key = null)), entry.start_time) {
                    checkLive(deadlineMillis)
                    nativeWrite { health.addMindfulness(entry, it) }
                }, bridgeJson.encodeToJsonElement(entry.copy(idempotency_key = null)))
            }
            "add_nutrition_batch" -> addNutritionBatch(args, client, deadlineMillis)
            "get_daily_summary" -> dailySummary(args, client, deadlineMillis, auth)
            "get_data_freshness" -> {
                require(args.isEmpty())
                health.freshness(freshnessTypes.filterValues { clients.permits(client, auth, it) }.keys)
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


    private suspend fun performModule(module: ActionModule, operation: String, args: JsonObject, client: ClientEntity, deadlineMillis: Long?): JsonObject {
        currentCoroutineContext().ensureActive()
        if (deadlineMillis != null && System.currentTimeMillis() >= deadlineMillis) throw RequestExpiredException()
        if (operation !in module.idempotent) return module.run(operation, args)
        val key = (args["idempotency_key"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val body = JsonObject(args.filterKeys { it != "idempotency_key" }.toSortedMap())
        var result: JsonObject? = null
        val written = clients.writeOnce(client, operation, key, body.toString(), Instant.now().toString()) { recordKey ->
            result = module.run(operation, body)
            WriteResult(record_id = recordKey, timestamp = Instant.now().toString())
        }
        return result?.let { JsonObject(it + ("record_id" to text(written.record_id))) }
            ?: obj("success" to JsonPrimitive(true), "replayed" to JsonPrimitive(true), "record_id" to text(written.record_id),
                "message" to text("This idempotency_key was already used; the action was not repeated."))
    }

    private fun writeResult(result: WriteResult, recorded: JsonElement) =
        JsonObject(bridgeJson.encodeToJsonElement(result).jsonObject + ("recorded_values" to recorded))

    /** One result per meal. A failed meal never blocks the others; retries are safe through per-entry idempotency keys. */
    private fun decodeNutrition(args: JsonObject): NutritionEntry {
        val (base, extra) = splitNutrients(args)
        return bridgeJson.decodeFromJsonElement<NutritionEntry>(base).copy(extra = extra)
    }

    private fun decodeChanges(args: JsonObject): NutritionChanges {
        val (base, extra) = splitNutrients(args)
        return bridgeJson.decodeFromJsonElement<NutritionChanges>(base).copy(extra = extra)
    }

    private suspend fun addNutritionBatch(args: JsonObject, client: ClientEntity, deadlineMillis: Long?): JsonObject {
        require(args.keys == setOf("entries"))
        val entries = args.getValue("entries").jsonArray.map { decodeNutrition(it.jsonObject) }
        require(entries.size in 1..MAX_BATCH) { "Provide 1-$MAX_BATCH entries." }
        require(entries.map { it.idempotency_key }.toSet().size == entries.size) { "Each entry needs its own idempotency_key." }
        val results = entries.mapIndexed { index, entry ->
            checkLive(deadlineMillis)
            try {
                entry.validate()
                val written = clients.writeOnce(client, "add_nutrition", entry.idempotency_key,
                    bridgeJson.encodeToString(NutritionEntry.serializer(), entry.copy(idempotency_key = null)), entry.timestamp) {
                    checkLive(deadlineMillis)
                    nativeWrite { health.addNutrition(entry, it) }
                }
                buildJsonObject {
                    put("index", index); put("success", true); put("meal_name", entry.meal_name)
                    put("record_id", written.record_id); put("timestamp", written.timestamp)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: SecurityException) { throw e }
            catch (e: RequestExpiredException) { throw e }
            catch (e: PhoneLockedException) { throw e }
            catch (e: PowerSavingException) { throw e }
            catch (e: IllegalArgumentException) { entryFailure(index, entry, "invalid_input", e.message ?: "Invalid entry.") }
            catch (e: IdempotencyConflictException) { entryFailure(index, entry, "idempotency_conflict", "This key was already used with different arguments.") }
            catch (e: OutcomeUnknownException) { entryFailure(index, entry, "outcome_unknown", "The write may or may not have completed. Retry with the same idempotency_key.") }
        }
        val succeeded = results.count { it["success"]?.jsonPrimitive?.boolean == true }
        return obj("success" to JsonPrimitive(succeeded == results.size), "succeeded" to JsonPrimitive(succeeded),
            "failed" to JsonPrimitive(results.size - succeeded), "results" to JsonArray(results))
    }

    private fun entryFailure(index: Int, entry: NutritionEntry, category: String, message: String) = buildJsonObject {
        put("index", index); put("success", false); put("meal_name", entry.meal_name)
        put("error_category", category); put("message", message)
    }

    private suspend fun dailySummary(args: JsonObject, client: ClientEntity, deadlineMillis: Long?, auth: OAuthBinding?): JsonObject {
        require(args.keys == setOf("date"))
        val raw = args.getValue("date").jsonPrimitive.also { require(it.isString) }.content
        val date = try { LocalDate.parse(raw) } catch (e: DateTimeParseException) { throw IllegalArgumentException("date must be YYYY-MM-DD.", e) }
        val zone = ZoneId.systemDefault()
        require(!date.isAfter(LocalDate.now(zone))) { "Future dates are not accepted." }
        val from = date.atStartOfDay(zone).toInstant()
        val to = minOf(date.plusDays(1).atStartOfDay(zone).toInstant(), Instant.now()).let { if (it > from) it else from.plusSeconds(1) }
        val start = from.atZone(zone).toOffsetDateTime().toString()
        val end = to.atZone(zone).toOffsetDateTime().toString()

        suspend fun section(permission: ClientPermission, needed: Set<String>, block: suspend () -> JsonObject): JsonElement {
            if (!clients.permits(client, auth, permission)) return obj("status" to text("not_permitted"))
            checkLive(deadlineMillis)
            if (needed.none { it in health.grantedPermissions() }) return obj("status" to text("health_permission_not_granted"))
            return try { JsonObject(obj("status" to text("ok")) + block()) }
            catch (e: CancellationException) { throw e }
            catch (e: SecurityException) { throw e }
            catch (e: RequestExpiredException) { throw e }
            catch (e: Exception) { obj("status" to text("error")) }
        }
        val sections = linkedMapOf<String, JsonElement>()
        sections["nutrition"] = section(ClientPermission.NUTRITION_READ, healthPermissionsFor("get_nutrition")) {
            val records = health.nutrition(start, end)
            val bySource = records.groupBy { it.source_application }.toSortedMap().map { (source, list) ->
                buildJsonObject {
                    put("source_application", source); put("meals", list.size)
                    put("calories_kcal", list.sumOf { it.calories_kcal ?: 0.0 }); put("protein_g", list.sumOf { it.protein_g ?: 0.0 })
                    put("carbs_g", list.sumOf { it.carbs_g ?: 0.0 }); put("fat_g", list.sumOf { it.fat_g ?: 0.0 })
                }
            }
            obj("totals_by_source" to JsonArray(bySource), "records" to bridgeJson.encodeToJsonElement(records))
        }
        sections["hydration"] = section(ClientPermission.HYDRATION_READ, healthPermissionsFor("get_hydration")) { health.hydration(start, end) }
        sections["steps"] = section(ClientPermission.STEPS_READ, healthPermissionsFor("get_steps")) {
            obj("total_steps" to JsonPrimitive(health.steps(start, end)), "sources" to JsonArray(health.stepSources(start, end).map(::text)))
        }
        sections["workouts"] = section(ClientPermission.WORKOUTS_READ, healthPermissionsFor("get_workouts")) { health.workouts(start, end) }
        sections["sleep"] = section(ClientPermission.SLEEP_READ, healthPermissionsFor("get_sleep")) { health.sleep(start, end) }
        sections["weight"] = section(ClientPermission.WEIGHT_READ, healthPermissionsFor("get_weight")) {
            obj("records" to bridgeJson.encodeToJsonElement(health.weight(start, end)))
        }
        sections["body_measurements"] = section(ClientPermission.BODY_READ, healthPermissionsFor("get_body_measurements")) { health.bodyMeasurements(start, end) }
        sections["active_calories"] = section(ClientPermission.ACTIVE_CALORIES_READ, healthPermissionsFor("get_active_calories")) { health.activeCalories(start, end) }
        sections["heart_rate"] = section(ClientPermission.HEART_RATE_READ, healthPermissionsFor("get_heart_rate")) { health.heartRate(start, end) }
        sections["oxygen"] = section(ClientPermission.OXYGEN_READ, healthPermissionsFor("get_oxygen_saturation")) { health.oxygenSaturation(start, end) }
        sections["vitals"] = section(ClientPermission.VITALS_READ, healthPermissionsFor("get_vitals")) { health.vitals(start, end) }
        sections["activity"] = section(ClientPermission.ACTIVITY_READ, healthPermissionsFor("get_activity_detail")) { health.activityDetail(start, end) }
        sections["mindfulness"] = section(ClientPermission.MINDFULNESS_READ, healthPermissionsFor("get_mindfulness")) { health.mindfulness(start, end) }
        return JsonObject(obj("date" to text(raw), "time_zone" to text(zone.id), "start_time" to text(start), "end_time" to text(end)) + sections)
    }

    /** Once a native write starts, failures other than a definite permission error are reported as uncertain. */
    private suspend fun <T> nativeWrite(block: suspend () -> T): T = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: RequestExpiredException) { throw e }
        catch (e: SecurityException) { throw e }
        catch (e: Exception) { throw OutcomeUnknownException() }

    companion object {
        const val MAX_BATCH = 20
        val DATA_TYPES = listOf("nutrition", "weight", "steps", "hydration", "sleep", "workouts", "heart_rate", "active_calories", "body_measurements", "oxygen", "vitals", "activity", "mindfulness")
        val healthOperations = mapOf(
            "get_gateway_status" to null, "get_permissions" to null,
            "get_nutrition" to ClientPermission.NUTRITION_READ, "add_nutrition" to ClientPermission.NUTRITION_WRITE,
            "get_weight" to ClientPermission.WEIGHT_READ, "add_weight" to ClientPermission.WEIGHT_WRITE,
            "get_steps" to ClientPermission.STEPS_READ, "delete_nutrition" to ClientPermission.NUTRITION_DELETE,
            "delete_weight" to ClientPermission.WEIGHT_DELETE,
            "update_nutrition" to ClientPermission.NUTRITION_WRITE, "add_nutrition_batch" to ClientPermission.NUTRITION_WRITE,
            "get_hydration" to ClientPermission.HYDRATION_READ, "add_hydration" to ClientPermission.HYDRATION_WRITE,
            "get_sleep" to ClientPermission.SLEEP_READ, "add_sleep" to ClientPermission.SLEEP_WRITE,
            "get_workouts" to ClientPermission.WORKOUTS_READ, "add_workout" to ClientPermission.WORKOUTS_WRITE,
            "get_heart_rate" to ClientPermission.HEART_RATE_READ, "get_active_calories" to ClientPermission.ACTIVE_CALORIES_READ,
            "get_body_measurements" to ClientPermission.BODY_READ, "add_body_measurement" to ClientPermission.BODY_WRITE,
            "get_daily_summary" to ClientPermission.SUMMARY_READ, "get_data_freshness" to ClientPermission.SUMMARY_READ,
            "get_oxygen_saturation" to ClientPermission.OXYGEN_READ, "add_oxygen_saturation" to ClientPermission.OXYGEN_WRITE,
            "get_vitals" to ClientPermission.VITALS_READ, "get_activity_detail" to ClientPermission.ACTIVITY_READ,
            "get_mindfulness" to ClientPermission.MINDFULNESS_READ, "add_mindfulness" to ClientPermission.MINDFULNESS_WRITE,
        )
        /** Every operation the relay accepts, across modules. */
        val permissions: Map<String, ClientPermission?> = healthOperations + dev.androidmcp.bridge.device.ClockModule.OPERATIONS +
            dev.androidmcp.bridge.device.NotifyModule.OPERATIONS
        private const val HC = "android.permission.health."
        /** Any one of the listed Health Connect permissions is enough to start the operation. */
        private val healthPermissions: Map<String, Set<String>> = mapOf(
            "get_nutrition" to setOf(HC + "READ_NUTRITION"),
            "add_nutrition" to setOf(HC + "WRITE_NUTRITION"),
            "update_nutrition" to setOf(HC + "WRITE_NUTRITION"),
            "add_nutrition_batch" to setOf(HC + "WRITE_NUTRITION"),
            "delete_nutrition" to setOf(HC + "WRITE_NUTRITION"),
            "get_weight" to setOf(HC + "READ_WEIGHT"),
            "add_weight" to setOf(HC + "WRITE_WEIGHT"),
            "delete_weight" to setOf(HC + "WRITE_WEIGHT"),
            "get_steps" to setOf(HC + "READ_STEPS"),
            "get_hydration" to setOf(HC + "READ_HYDRATION"), "add_hydration" to setOf(HC + "WRITE_HYDRATION"),
            "get_sleep" to setOf(HC + "READ_SLEEP"), "add_sleep" to setOf(HC + "WRITE_SLEEP"),
            "get_workouts" to setOf(HC + "READ_EXERCISE"), "add_workout" to setOf(HC + "WRITE_EXERCISE"),
            "get_heart_rate" to setOf(HC + "READ_HEART_RATE"),
            "get_active_calories" to setOf(HC + "READ_ACTIVE_CALORIES_BURNED"),
            "get_body_measurements" to setOf(HC + "READ_BODY_FAT", HC + "READ_LEAN_BODY_MASS", HC + "READ_HEIGHT", HC + "READ_BONE_MASS", HC + "READ_BODY_WATER_MASS"),
            "add_body_measurement" to setOf(HC + "WRITE_BODY_FAT", HC + "WRITE_LEAN_BODY_MASS", HC + "WRITE_HEIGHT", HC + "WRITE_BONE_MASS", HC + "WRITE_BODY_WATER_MASS"),
            "get_oxygen_saturation" to setOf(HC + "READ_OXYGEN_SATURATION"), "add_oxygen_saturation" to setOf(HC + "WRITE_OXYGEN_SATURATION"),
            "get_vitals" to setOf(HC + "READ_HEART_RATE_VARIABILITY", HC + "READ_RESPIRATORY_RATE"),
            "get_activity_detail" to setOf(HC + "READ_DISTANCE", HC + "READ_FLOORS_CLIMBED", HC + "READ_ELEVATION_GAINED", HC + "READ_SPEED",
                HC + "READ_TOTAL_CALORIES_BURNED", HC + "READ_BASAL_METABOLIC_RATE"),
            "get_mindfulness" to setOf(HC + "READ_MINDFULNESS"), "add_mindfulness" to setOf(HC + "WRITE_MINDFULNESS"),
        )
        /** Summary and freshness have no single Health Connect permission; each section is checked on its own. */
        private val gated = healthPermissions.keys + setOf("get_daily_summary", "get_data_freshness")
        private fun healthPermissionsFor(operation: String) = healthPermissions.getValue(operation)
        private val freshnessTypes = mapOf(
            "nutrition" to ClientPermission.NUTRITION_READ, "weight" to ClientPermission.WEIGHT_READ, "steps" to ClientPermission.STEPS_READ,
            "hydration" to ClientPermission.HYDRATION_READ, "sleep" to ClientPermission.SLEEP_READ,
            "workouts" to ClientPermission.WORKOUTS_READ, "heart_rate" to ClientPermission.HEART_RATE_READ,
            "active_calories" to ClientPermission.ACTIVE_CALORIES_READ, "body" to ClientPermission.BODY_READ,
            "oxygen" to ClientPermission.OXYGEN_READ, "vitals" to ClientPermission.VITALS_READ,
            "activity" to ClientPermission.ACTIVITY_READ, "mindfulness" to ClientPermission.MINDFULNESS_READ,
        )
    }
}
