package dev.healthbridge.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Volume
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.reflect.KClass

class HealthConnectManager(private val context: Context) : HealthRepository {
    private val client get() = HealthConnectClient.getOrCreate(context)
    override fun available() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    override suspend fun grantedPermissions(): Set<String> =
        if (available()) client.permissionController.getGrantedPermissions() else emptySet()

    private suspend fun check(permission: String) {
        check(available()) { "Health Connect is unavailable. Install or update Health Connect." }
        if (permission !in grantedPermissions()) throw SecurityException("Required Health Connect permission is not granted.")
    }

    override suspend fun nutrition(start: String, end: String): List<NutritionData> {
        check(HealthPermission.getReadPermission(NutritionRecord::class))
        val (from, to) = dateRange(start, end)
        val result = mutableListOf<NutritionData>()
        var page: String? = null
        do {
            checkHealthOperation()
            val response = client.readRecords(ReadRecordsRequest(
                NutritionRecord::class, TimeRangeFilter.between(from, to), pageSize = 500, pageToken = page,
            ))
            response.records.forEach { record ->
                result += NutritionData(
                    record.metadata.id, record.name, mealName(record.mealType),
                    record.startTime.atOffset(record.startZoneOffset ?: ZoneId.systemDefault().rules.getOffset(record.startTime)).toString(),
                    record.endTime.atOffset(record.endZoneOffset ?: ZoneId.systemDefault().rules.getOffset(record.endTime)).toString(),
                    record.energy?.inKilocalories, record.protein?.inGrams,
                    record.totalCarbohydrate?.inGrams, record.totalFat?.inGrams,
                    record.sugar?.inGrams, record.dietaryFiber?.inGrams, record.sodium?.inMilligrams,
                    record.metadata.dataOrigin.packageName,
                )
            }
            check(result.size <= 5000) { "Too many records. Use a smaller date range." }
            page = response.pageToken
        } while (page != null)
        return result
    }

    override suspend fun addNutrition(entry: NutritionEntry, recordKey: String): WriteResult {
        entry.validate()
        check(HealthPermission.getWritePermission(NutritionRecord::class))
        val time = parseTime(entry.timestamp)
        val record = nutritionRecord(entry, time.offset, recordKey, 1)
        checkHealthOperation()
        val id = client.insertRecords(listOf(record)).recordIdsList.single()
        return WriteResult(record_id = id, timestamp = entry.timestamp)
    }

    private fun nutritionRecord(entry: NutritionEntry, offset: ZoneOffset, recordKey: String, version: Long): NutritionRecord {
        val time = parseTime(entry.timestamp)
        return NutritionRecord(
            startTime = time.toInstant(), startZoneOffset = offset,
            endTime = time.toInstant().plusSeconds(1), endZoneOffset = offset,
            metadata = Metadata.manualEntry(clientRecordId = recordKey, clientRecordVersion = version),
            name = entry.meal_name, mealType = mealType(entry.meal_type),
            energy = Energy.kilocalories(entry.calories_kcal),
            protein = Mass.grams(entry.protein_g), totalCarbohydrate = Mass.grams(entry.carbs_g),
            totalFat = Mass.grams(entry.fat_g), sugar = entry.sugar_g?.let(Mass::grams),
            dietaryFiber = entry.fiber_g?.let(Mass::grams), sodium = entry.sodium_mg?.let(Mass::milligrams),
        )
    }

    /** Rewrites a HealthBridge-owned meal in place: same client record id, higher version, so no duplicate appears. */
    override suspend fun updateNutrition(id: String, changes: NutritionChanges): WriteResult {
        require(id.isNotBlank() && id.length <= 200) { "Invalid record ID." }
        require(!changes.isEmpty()) { "Provide at least one change." }
        check(HealthPermission.getReadPermission(NutritionRecord::class))
        check(HealthPermission.getWritePermission(NutritionRecord::class))
        checkHealthOperation()
        val old = client.readRecord(NutritionRecord::class, id).record
        require(old.metadata.dataOrigin.packageName == context.packageName) { "Only HealthBridge-owned records can be updated." }
        val key = old.metadata.clientRecordId
        require(key != null) { "This record cannot be updated in place." }
        val oldOffset = old.startZoneOffset ?: ZoneId.systemDefault().rules.getOffset(old.startTime)
        val merged = NutritionEntry(
            meal_name = changes.meal_name ?: old.name ?: "Meal",
            meal_type = changes.meal_type ?: mealName(old.mealType),
            timestamp = changes.timestamp ?: old.startTime.atOffset(oldOffset).toString(),
            calories_kcal = changes.calories_kcal ?: old.energy?.inKilocalories ?: 0.0,
            protein_g = changes.protein_g ?: old.protein?.inGrams ?: 0.0,
            carbs_g = changes.carbs_g ?: old.totalCarbohydrate?.inGrams ?: 0.0,
            fat_g = changes.fat_g ?: old.totalFat?.inGrams ?: 0.0,
            sugar_g = changes.sugar_g ?: old.sugar?.inGrams,
            fiber_g = changes.fiber_g ?: old.dietaryFiber?.inGrams,
            sodium_mg = changes.sodium_mg ?: old.sodium?.inMilligrams,
        )
        merged.validate()
        val offset = changes.timestamp?.let { parseTime(it).offset } ?: oldOffset
        val record = nutritionRecord(merged, offset, key, old.metadata.clientRecordVersion + 1)
        checkHealthOperation()
        val newId = client.insertRecords(listOf(record)).recordIdsList.single()
        return WriteResult(record_id = newId, timestamp = merged.timestamp)
    }

    private fun offsetOf(instant: Instant, offset: ZoneOffset?) = instant.atOffset(offset ?: ZoneId.systemDefault().rules.getOffset(instant)).toString()

    private suspend fun <T : Record> readAll(type: KClass<T>, from: Instant, to: Instant): List<T> {
        val result = mutableListOf<T>()
        var page: String? = null
        do {
            checkHealthOperation()
            val response = client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(from, to), pageSize = 500, pageToken = page))
            result += response.records
            check(result.size <= 5000) { "Too many records. Use a smaller date range." }
            page = response.pageToken
        } while (page != null)
        return result
    }

    private suspend fun aggregate(metrics: Set<androidx.health.connect.client.aggregate.AggregateMetric<*>>, from: Instant, to: Instant): AggregationResult {
        checkHealthOperation()
        return client.aggregate(AggregateRequest(metrics, TimeRangeFilter.between(from, to)))
    }

    private fun sources(result: AggregationResult) = buildJsonArray { result.dataOrigins.map { it.packageName }.sorted().forEach { add(JsonPrimitive(it)) } }

    override suspend fun stepSources(start: String, end: String): List<String> {
        check(HealthPermission.getReadPermission(StepsRecord::class))
        val (from, to) = dateRange(start, end)
        return aggregate(setOf(StepsRecord.COUNT_TOTAL), from, to).dataOrigins.map { it.packageName }.sorted()
    }

    override suspend fun hydration(start: String, end: String): JsonObject {
        check(HealthPermission.getReadPermission(HydrationRecord::class))
        val (from, to) = dateRange(start, end)
        val records = readAll(HydrationRecord::class, from, to)
        val total = aggregate(setOf(HydrationRecord.VOLUME_TOTAL), from, to)
        return buildJsonObject {
            put("total_ml", total[HydrationRecord.VOLUME_TOTAL]?.inMilliliters ?: 0.0)
            put("total_sources", sources(total))
            put("records", buildJsonArray {
                records.forEach { r -> add(buildJsonObject {
                    put("id", r.metadata.id); put("volume_ml", r.volume.inMilliliters)
                    put("start_time", offsetOf(r.startTime, r.startZoneOffset)); put("end_time", offsetOf(r.endTime, r.endZoneOffset))
                    put("source_application", r.metadata.dataOrigin.packageName)
                }) }
            })
        }
    }

    override suspend fun addHydration(entry: HydrationEntry, recordKey: String): WriteResult {
        entry.validate()
        check(HealthPermission.getWritePermission(HydrationRecord::class))
        val time = parseTime(entry.timestamp)
        val record = HydrationRecord(time.toInstant(), time.offset, time.toInstant().plusSeconds(1), time.offset,
            Volume.milliliters(entry.volume_ml), Metadata.manualEntry(clientRecordId = recordKey, clientRecordVersion = 1))
        checkHealthOperation()
        return WriteResult(record_id = client.insertRecords(listOf(record)).recordIdsList.single(), timestamp = entry.timestamp)
    }

    override suspend fun sleep(start: String, end: String): JsonObject {
        check(HealthPermission.getReadPermission(SleepSessionRecord::class))
        val (from, to) = dateRange(start, end)
        val records = readAll(SleepSessionRecord::class, from, to)
        return buildJsonObject {
            put("total_minutes", records.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() })
            put("records", buildJsonArray {
                records.forEach { r -> add(buildJsonObject {
                    put("id", r.metadata.id); put("title", r.title)
                    put("start_time", offsetOf(r.startTime, r.startZoneOffset)); put("end_time", offsetOf(r.endTime, r.endZoneOffset))
                    put("duration_minutes", Duration.between(r.startTime, r.endTime).toMinutes())
                    put("stage_minutes", buildJsonObject {
                        r.stages.groupBy { SleepSessionRecord.STAGE_TYPE_INT_TO_STRING_MAP[it.stage] ?: "unknown" }
                            .forEach { (name, stages) -> put(name, stages.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }) }
                    })
                    put("source_application", r.metadata.dataOrigin.packageName)
                }) }
            })
        }
    }

    override suspend fun addSleep(entry: SleepEntry, recordKey: String): WriteResult {
        entry.validate()
        check(HealthPermission.getWritePermission(SleepSessionRecord::class))
        val start = parseTime(entry.start_time)
        val end = parseTime(entry.end_time)
        val record = SleepSessionRecord(start.toInstant(), start.offset, end.toInstant(), end.offset,
            Metadata.manualEntry(clientRecordId = recordKey, clientRecordVersion = 1), entry.title)
        checkHealthOperation()
        return WriteResult(record_id = client.insertRecords(listOf(record)).recordIdsList.single(), timestamp = entry.start_time)
    }

    override suspend fun workouts(start: String, end: String): JsonObject {
        check(HealthPermission.getReadPermission(ExerciseSessionRecord::class))
        val (from, to) = dateRange(start, end)
        val records = readAll(ExerciseSessionRecord::class, from, to)
        return buildJsonObject {
            put("total_minutes", records.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() })
            put("records", buildJsonArray {
                records.forEach { r -> add(buildJsonObject {
                    put("id", r.metadata.id); put("exercise_type", exerciseName(r.exerciseType)); put("title", r.title); put("notes", r.notes)
                    put("start_time", offsetOf(r.startTime, r.startZoneOffset)); put("end_time", offsetOf(r.endTime, r.endZoneOffset))
                    put("duration_minutes", Duration.between(r.startTime, r.endTime).toMinutes())
                    put("source_application", r.metadata.dataOrigin.packageName)
                }) }
            })
        }
    }

    override suspend fun addWorkout(entry: WorkoutEntry, recordKey: String): WriteResult {
        entry.validate()
        check(HealthPermission.getWritePermission(ExerciseSessionRecord::class))
        val start = parseTime(entry.start_time)
        val end = parseTime(entry.end_time)
        val record = ExerciseSessionRecord(start.toInstant(), start.offset, end.toInstant(), end.offset,
            Metadata.manualEntry(clientRecordId = recordKey, clientRecordVersion = 1), exerciseType(entry.exercise_type),
            entry.title, entry.notes)
        checkHealthOperation()
        return WriteResult(record_id = client.insertRecords(listOf(record)).recordIdsList.single(), timestamp = entry.start_time)
    }

    override suspend fun heartRate(start: String, end: String): JsonObject {
        check(HealthPermission.getReadPermission(HeartRateRecord::class))
        val (from, to) = dateRange(start, end)
        val result = aggregate(setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX), from, to)
        val resting = if (HealthPermission.getReadPermission(RestingHeartRateRecord::class) in grantedPermissions())
            aggregate(setOf(RestingHeartRateRecord.BPM_AVG), from, to)[RestingHeartRateRecord.BPM_AVG] else null
        return buildJsonObject {
            put("average_bpm", result[HeartRateRecord.BPM_AVG]); put("min_bpm", result[HeartRateRecord.BPM_MIN])
            put("max_bpm", result[HeartRateRecord.BPM_MAX]); put("resting_average_bpm", resting)
            put("sources", sources(result))
        }
    }

    override suspend fun activeCalories(start: String, end: String): JsonObject {
        check(HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class))
        val (from, to) = dateRange(start, end)
        val result = aggregate(setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), from, to)
        return buildJsonObject {
            put("active_calories_kcal", result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0)
            put("sources", sources(result))
        }
    }

    override suspend fun bodyMeasurements(start: String, end: String): JsonObject {
        val granted = grantedPermissions()
        val fatOk = HealthPermission.getReadPermission(BodyFatRecord::class) in granted
        val leanOk = HealthPermission.getReadPermission(LeanBodyMassRecord::class) in granted
        if (!fatOk && !leanOk) throw SecurityException("Required Health Connect permission is not granted.")
        check(available()) { "Health Connect is unavailable. Install or update Health Connect." }
        val (from, to) = dateRange(start, end)
        return buildJsonObject {
            put("body_fat", if (!fatOk) JsonNull else buildJsonArray {
                readAll(BodyFatRecord::class, from, to).forEach { r -> add(buildJsonObject {
                    put("id", r.metadata.id); put("percent", r.percentage.value); put("timestamp", offsetOf(r.time, r.zoneOffset))
                    put("source_application", r.metadata.dataOrigin.packageName)
                }) }
            })
            put("lean_body_mass", if (!leanOk) JsonNull else buildJsonArray {
                readAll(LeanBodyMassRecord::class, from, to).forEach { r -> add(buildJsonObject {
                    put("id", r.metadata.id); put("kg", r.mass.inKilograms); put("timestamp", offsetOf(r.time, r.zoneOffset))
                    put("source_application", r.metadata.dataOrigin.packageName)
                }) }
            })
        }
    }

    override suspend fun addBodyMeasurement(entry: BodyMeasurementEntry, recordKey: String): WriteResult {
        entry.validate()
        val granted = grantedPermissions()
        if (entry.body_fat_percent != null && HealthPermission.getWritePermission(BodyFatRecord::class) !in granted ||
            entry.lean_body_mass_kg != null && HealthPermission.getWritePermission(LeanBodyMassRecord::class) !in granted)
            throw SecurityException("Required Health Connect permission is not granted.")
        check(available()) { "Health Connect is unavailable. Install or update Health Connect." }
        val time = parseTime(entry.timestamp)
        val records = mutableListOf<Record>()
        entry.body_fat_percent?.let {
            records += BodyFatRecord(time.toInstant(), time.offset, Percentage(it),
                Metadata.manualEntry(clientRecordId = "$recordKey:fat", clientRecordVersion = 1))
        }
        entry.lean_body_mass_kg?.let {
            records += LeanBodyMassRecord(time.toInstant(), time.offset, Mass.kilograms(it),
                Metadata.manualEntry(clientRecordId = "$recordKey:lean", clientRecordVersion = 1))
        }
        checkHealthOperation()
        return WriteResult(record_id = client.insertRecords(records).recordIdsList.joinToString(","), timestamp = entry.timestamp)
    }

    override suspend fun freshness(types: Set<String>): JsonObject {
        check(available()) { "Health Connect is unavailable. Install or update Health Connect." }
        val granted = grantedPermissions()
        val now = Instant.now()
        val from = now.minus(Duration.ofDays(30))
        suspend fun <T : Record> scan(type: KClass<T>) = run {
            checkHealthOperation()
            client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(from, now), ascendingOrder = false, pageSize = 200)).records
        }
        fun summary(records: List<Record>) = buildJsonArray {
            records.groupBy { it.metadata.dataOrigin.packageName }.toSortedMap().forEach { (app, list) ->
                val latest = list.maxOf { recordTime(it) }
                add(buildJsonObject {
                    put("source_application", app)
                    put("latest_record_time", latest.toString())
                    put("last_modified_time", list.maxOf { it.metadata.lastModifiedTime }.toString())
                    put("recent_record_count", list.size)
                })
            }
        }
        val kinds: Map<String, List<KClass<out Record>>> = mapOf(
            "nutrition" to listOf(NutritionRecord::class), "weight" to listOf(WeightRecord::class), "steps" to listOf(StepsRecord::class),
            "hydration" to listOf(HydrationRecord::class), "sleep" to listOf(SleepSessionRecord::class),
            "workouts" to listOf(ExerciseSessionRecord::class), "heart_rate" to listOf(HeartRateRecord::class),
            "active_calories" to listOf(ActiveCaloriesBurnedRecord::class),
            "body" to listOf(BodyFatRecord::class, LeanBodyMassRecord::class),
        )
        return buildJsonObject {
            put("window_days", 30)
            kinds.forEach { (name, classes) ->
                put(name, when {
                    name !in types -> buildJsonObject { put("status", "not_permitted") }
                    classes.none { HealthPermission.getReadPermission(it) in granted } -> buildJsonObject { put("status", "health_permission_not_granted") }
                    else -> {
                        val records = classes.filter { HealthPermission.getReadPermission(it) in granted }.flatMap { scan(it) }
                        buildJsonObject { put("status", if (records.isEmpty()) "no_data" else "ok"); put("sources", summary(records)) }
                    }
                })
            }
        }
    }

    private fun recordTime(record: Record): Instant = when (record) {
        is NutritionRecord -> record.endTime
        is StepsRecord -> record.endTime
        is HydrationRecord -> record.endTime
        is SleepSessionRecord -> record.endTime
        is ExerciseSessionRecord -> record.endTime
        is HeartRateRecord -> record.endTime
        is ActiveCaloriesBurnedRecord -> record.endTime
        is WeightRecord -> record.time
        is BodyFatRecord -> record.time
        is LeanBodyMassRecord -> record.time
        else -> record.metadata.lastModifiedTime
    }

    override suspend fun weight(start: String, end: String): List<WeightData> {
        check(HealthPermission.getReadPermission(WeightRecord::class))
        val (from, to) = dateRange(start, end)
        val result = mutableListOf<WeightData>()
        var page: String? = null
        do {
            checkHealthOperation()
            val response = client.readRecords(ReadRecordsRequest(
                WeightRecord::class, TimeRangeFilter.between(from, to), pageSize = 500, pageToken = page,
            ))
            result += response.records.map {
                WeightData(it.metadata.id, it.weight.inKilograms,
                    it.time.atOffset(it.zoneOffset ?: ZoneId.systemDefault().rules.getOffset(it.time)).toString(),
                    it.metadata.dataOrigin.packageName)
            }
            check(result.size <= 5000) { "Too many records. Use a smaller date range." }
            page = response.pageToken
        } while (page != null)
        return result
    }

    override suspend fun addWeight(entry: WeightEntry, recordKey: String): WriteResult {
        entry.validate()
        check(HealthPermission.getWritePermission(WeightRecord::class))
        val time = parseTime(entry.timestamp)
        val record = WeightRecord(time.toInstant(), time.offset, Mass.kilograms(entry.weight_kg),
            Metadata.manualEntry(clientRecordId = recordKey, clientRecordVersion = 1))
        checkHealthOperation()
        return WriteResult(record_id = client.insertRecords(listOf(record)).recordIdsList.single(), timestamp = entry.timestamp)
    }

    override suspend fun steps(start: String, end: String): Long {
        check(HealthPermission.getReadPermission(StepsRecord::class))
        val (from, to) = dateRange(start, end)
        checkHealthOperation()
        return client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(from, to)))[StepsRecord.COUNT_TOTAL] ?: 0
    }

    suspend fun readTestRecord(id: String): NutritionRecord {
        check(HealthPermission.getReadPermission(NutritionRecord::class))
        checkHealthOperation()
        return client.readRecord(NutritionRecord::class, id).record
    }

    suspend fun deleteTestRecord(id: String) {
        check(HealthPermission.getWritePermission(NutritionRecord::class))
        val record = readTestRecord(id)
        require(record.metadata.dataOrigin.packageName == context.packageName &&
            record.metadata.clientRecordId?.startsWith("healthbridge-test:") == true) { "Only HealthBridge test records can be deleted here." }
        checkHealthOperation()
        client.deleteRecords(NutritionRecord::class, listOf(id), emptyList())
    }

    override suspend fun deleteNutrition(id: String) {
        require(id.isNotBlank() && id.length <= 200) { "Invalid record ID." }
        check(HealthPermission.getReadPermission(NutritionRecord::class))
        check(HealthPermission.getWritePermission(NutritionRecord::class))
        checkHealthOperation()
        val record = client.readRecord(NutritionRecord::class, id).record
        require(record.metadata.dataOrigin.packageName == context.packageName) { "Only HealthBridge-owned records can be deleted." }
        checkHealthOperation()
        client.deleteRecords(NutritionRecord::class, listOf(id), emptyList())
    }

    override suspend fun deleteWeight(id: String) {
        require(id.isNotBlank() && id.length <= 200) { "Invalid record ID." }
        check(HealthPermission.getReadPermission(WeightRecord::class))
        check(HealthPermission.getWritePermission(WeightRecord::class))
        checkHealthOperation()
        val record = client.readRecord(WeightRecord::class, id).record
        require(record.metadata.dataOrigin.packageName == context.packageName) { "Only HealthBridge-owned records can be deleted." }
        checkHealthOperation()
        client.deleteRecords(WeightRecord::class, listOf(id), emptyList())
    }

    companion object {
        val permissions = setOf(
            HealthPermission.getReadPermission(NutritionRecord::class),
            HealthPermission.getWritePermission(NutritionRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getWritePermission(WeightRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(HydrationRecord::class),
            HealthPermission.getWritePermission(HydrationRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getWritePermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getWritePermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(LeanBodyMassRecord::class),
            HealthPermission.getWritePermission(LeanBodyMassRecord::class),
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
        )
        private val exerciseTypes = mapOf(
            "strength_training" to ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            "running" to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            "walking" to ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
            "cycling" to ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            "swimming" to ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            "yoga" to ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
            "pilates" to ExerciseSessionRecord.EXERCISE_TYPE_PILATES,
            "hiit" to ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
            "hiking" to ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
            "rowing" to ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
            "elliptical" to ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL,
            "stair_climbing" to ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING,
            "stretching" to ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING,
            "dancing" to ExerciseSessionRecord.EXERCISE_TYPE_DANCING,
            "calisthenics" to ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
        )
        fun exerciseType(name: String) = exerciseTypes[name] ?: ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
        fun exerciseName(type: Int) = exerciseTypes.entries.firstOrNull { it.value == type }?.key
            ?: ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP[type] ?: "other"
        fun mealType(name: String) = when (name) {
            "breakfast" -> MealType.MEAL_TYPE_BREAKFAST
            "lunch" -> MealType.MEAL_TYPE_LUNCH
            "dinner" -> MealType.MEAL_TYPE_DINNER
            "snack" -> MealType.MEAL_TYPE_SNACK
            else -> MealType.MEAL_TYPE_UNKNOWN
        }
        fun mealName(type: Int) = when (type) {
            MealType.MEAL_TYPE_BREAKFAST -> "breakfast"
            MealType.MEAL_TYPE_LUNCH -> "lunch"
            MealType.MEAL_TYPE_DINNER -> "dinner"
            MealType.MEAL_TYPE_SNACK -> "snack"
            else -> "unknown"
        }
    }
}
