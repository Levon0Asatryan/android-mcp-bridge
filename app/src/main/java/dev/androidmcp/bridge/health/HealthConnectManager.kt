@file:OptIn(androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi::class)

package dev.androidmcp.bridge.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.units.Length
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
                    record.metadata.dataOrigin.packageName, nutrientsOf(record),
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
            saturatedFat = mass(entry, "saturated_fat_g"), unsaturatedFat = mass(entry, "unsaturated_fat_g"),
            monounsaturatedFat = mass(entry, "monounsaturated_fat_g"), polyunsaturatedFat = mass(entry, "polyunsaturated_fat_g"),
            transFat = mass(entry, "trans_fat_g"), cholesterol = mass(entry, "cholesterol_mg"),
            potassium = mass(entry, "potassium_mg"), calcium = mass(entry, "calcium_mg"), iron = mass(entry, "iron_mg"),
            magnesium = mass(entry, "magnesium_mg"), zinc = mass(entry, "zinc_mg"), phosphorus = mass(entry, "phosphorus_mg"),
            copper = mass(entry, "copper_mg"), manganese = mass(entry, "manganese_mg"), chloride = mass(entry, "chloride_mg"),
            selenium = mass(entry, "selenium_mcg"), chromium = mass(entry, "chromium_mcg"), molybdenum = mass(entry, "molybdenum_mcg"),
            iodine = mass(entry, "iodine_mcg"), vitaminA = mass(entry, "vitamin_a_mcg"), vitaminB6 = mass(entry, "vitamin_b6_mg"),
            vitaminB12 = mass(entry, "vitamin_b12_mcg"), vitaminC = mass(entry, "vitamin_c_mg"), vitaminD = mass(entry, "vitamin_d_mcg"),
            vitaminE = mass(entry, "vitamin_e_mg"), vitaminK = mass(entry, "vitamin_k_mcg"), thiamin = mass(entry, "thiamin_mg"),
            riboflavin = mass(entry, "riboflavin_mg"), niacin = mass(entry, "niacin_mg"), folate = mass(entry, "folate_mcg"),
            folicAcid = mass(entry, "folic_acid_mcg"), biotin = mass(entry, "biotin_mcg"),
            pantothenicAcid = mass(entry, "pantothenic_acid_mg"), caffeine = mass(entry, "caffeine_mg"),
        )
    }

    private fun mass(entry: NutritionEntry, key: String): Mass? = entry.extra[key]?.let {
        when {
            key.endsWith("_mcg") -> Mass.micrograms(it)
            key.endsWith("_mg") -> Mass.milligrams(it)
            else -> Mass.grams(it)
        }
    }

    private fun nutrientsOf(r: NutritionRecord): Map<String, Double> {
        val values = mapOf(
            "saturated_fat_g" to r.saturatedFat?.inGrams, "unsaturated_fat_g" to r.unsaturatedFat?.inGrams,
            "monounsaturated_fat_g" to r.monounsaturatedFat?.inGrams, "polyunsaturated_fat_g" to r.polyunsaturatedFat?.inGrams,
            "trans_fat_g" to r.transFat?.inGrams, "cholesterol_mg" to r.cholesterol?.inMilligrams,
            "potassium_mg" to r.potassium?.inMilligrams, "calcium_mg" to r.calcium?.inMilligrams, "iron_mg" to r.iron?.inMilligrams,
            "magnesium_mg" to r.magnesium?.inMilligrams, "zinc_mg" to r.zinc?.inMilligrams, "phosphorus_mg" to r.phosphorus?.inMilligrams,
            "copper_mg" to r.copper?.inMilligrams, "manganese_mg" to r.manganese?.inMilligrams, "chloride_mg" to r.chloride?.inMilligrams,
            "selenium_mcg" to r.selenium?.inMicrograms, "chromium_mcg" to r.chromium?.inMicrograms,
            "molybdenum_mcg" to r.molybdenum?.inMicrograms, "iodine_mcg" to r.iodine?.inMicrograms,
            "vitamin_a_mcg" to r.vitaminA?.inMicrograms, "vitamin_b6_mg" to r.vitaminB6?.inMilligrams,
            "vitamin_b12_mcg" to r.vitaminB12?.inMicrograms, "vitamin_c_mg" to r.vitaminC?.inMilligrams,
            "vitamin_d_mcg" to r.vitaminD?.inMicrograms, "vitamin_e_mg" to r.vitaminE?.inMilligrams,
            "vitamin_k_mcg" to r.vitaminK?.inMicrograms, "thiamin_mg" to r.thiamin?.inMilligrams,
            "riboflavin_mg" to r.riboflavin?.inMilligrams, "niacin_mg" to r.niacin?.inMilligrams,
            "folate_mcg" to r.folate?.inMicrograms, "folic_acid_mcg" to r.folicAcid?.inMicrograms,
            "biotin_mcg" to r.biotin?.inMicrograms, "pantothenic_acid_mg" to r.pantothenicAcid?.inMilligrams,
            "caffeine_mg" to r.caffeine?.inMilligrams,
        )
        return values.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
    }

    /** Rewrites a Android MCP Bridge-owned meal in place: same client record id, higher version, so no duplicate appears. */
    override suspend fun updateNutrition(id: String, changes: NutritionChanges): WriteResult {
        require(id.isNotBlank() && id.length <= 200) { "Invalid record ID." }
        require(!changes.isEmpty()) { "Provide at least one change." }
        check(HealthPermission.getReadPermission(NutritionRecord::class))
        check(HealthPermission.getWritePermission(NutritionRecord::class))
        checkHealthOperation()
        val old = client.readRecord(NutritionRecord::class, id).record
        require(old.metadata.dataOrigin.packageName == context.packageName) { "Only Android MCP Bridge-owned records can be updated." }
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
            extra = nutrientsOf(old) + changes.extra,
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

    override suspend fun updateHydration(id: String, changes: HydrationChanges): WriteResult {
        require(id.isNotBlank() && id.length <= 200) { "Invalid record ID." }
        changes.validate()
        check(HealthPermission.getReadPermission(HydrationRecord::class))
        check(HealthPermission.getWritePermission(HydrationRecord::class))
        checkHealthOperation()
        val old = client.readRecord(HydrationRecord::class, id).record
        require(old.metadata.dataOrigin.packageName == context.packageName) { "Only Android MCP Bridge-owned records can be updated." }
        val key = old.metadata.clientRecordId
        require(key != null) { "This record cannot be updated in place." }
        val oldOffset = old.startZoneOffset ?: ZoneId.systemDefault().rules.getOffset(old.startTime)
        val time = changes.timestamp?.let { parseTime(it) } ?: old.startTime.atOffset(oldOffset)
        val volume = changes.volume_ml ?: old.volume.inMilliliters
        val record = HydrationRecord(time.toInstant(), time.offset, time.toInstant().plusSeconds(1), time.offset,
            Volume.milliliters(volume), Metadata.manualEntry(clientRecordId = key, clientRecordVersion = old.metadata.clientRecordVersion + 1))
        checkHealthOperation()
        return WriteResult(record_id = client.insertRecords(listOf(record)).recordIdsList.single(), timestamp = time.toString())
    }

    override suspend fun deleteHydration(id: String) {
        require(id.isNotBlank() && id.length <= 200) { "Invalid record ID." }
        check(HealthPermission.getReadPermission(HydrationRecord::class))
        check(HealthPermission.getWritePermission(HydrationRecord::class))
        checkHealthOperation()
        val record = client.readRecord(HydrationRecord::class, id).record
        require(record.metadata.dataOrigin.packageName == context.packageName) { "Only Android MCP Bridge-owned records can be deleted." }
        checkHealthOperation()
        client.deleteRecords(HydrationRecord::class, listOf(id), emptyList())
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
        val heightOk = HealthPermission.getReadPermission(HeightRecord::class) in granted
        val boneOk = HealthPermission.getReadPermission(BoneMassRecord::class) in granted
        val waterOk = HealthPermission.getReadPermission(BodyWaterMassRecord::class) in granted
        if (!fatOk && !leanOk && !heightOk && !boneOk && !waterOk) throw SecurityException("Required Health Connect permission is not granted.")
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
            put("height", if (!heightOk) JsonNull else buildJsonArray {
                readAll(HeightRecord::class, from, to).forEach { r -> add(buildJsonObject {
                    put("id", r.metadata.id); put("cm", r.height.inMeters * 100); put("timestamp", offsetOf(r.time, r.zoneOffset))
                    put("source_application", r.metadata.dataOrigin.packageName)
                }) }
            })
            put("bone_mass", if (!boneOk) JsonNull else buildJsonArray {
                readAll(BoneMassRecord::class, from, to).forEach { r -> add(buildJsonObject {
                    put("id", r.metadata.id); put("kg", r.mass.inKilograms); put("timestamp", offsetOf(r.time, r.zoneOffset))
                    put("source_application", r.metadata.dataOrigin.packageName)
                }) }
            })
            put("body_water_mass", if (!waterOk) JsonNull else buildJsonArray {
                readAll(BodyWaterMassRecord::class, from, to).forEach { r -> add(buildJsonObject {
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
            entry.lean_body_mass_kg != null && HealthPermission.getWritePermission(LeanBodyMassRecord::class) !in granted ||
            entry.height_cm != null && HealthPermission.getWritePermission(HeightRecord::class) !in granted ||
            entry.bone_mass_kg != null && HealthPermission.getWritePermission(BoneMassRecord::class) !in granted ||
            entry.body_water_mass_kg != null && HealthPermission.getWritePermission(BodyWaterMassRecord::class) !in granted)
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
        entry.height_cm?.let {
            records += HeightRecord(time.toInstant(), time.offset, Length.meters(it / 100),
                Metadata.manualEntry(clientRecordId = "$recordKey:height", clientRecordVersion = 1))
        }
        entry.bone_mass_kg?.let {
            records += BoneMassRecord(time.toInstant(), time.offset, Mass.kilograms(it),
                Metadata.manualEntry(clientRecordId = "$recordKey:bone", clientRecordVersion = 1))
        }
        entry.body_water_mass_kg?.let {
            records += BodyWaterMassRecord(time.toInstant(), time.offset, Mass.kilograms(it),
                Metadata.manualEntry(clientRecordId = "$recordKey:water", clientRecordVersion = 1))
        }
        checkHealthOperation()
        return WriteResult(record_id = client.insertRecords(records).recordIdsList.joinToString(","), timestamp = entry.timestamp)
    }

    override suspend fun oxygenSaturation(start: String, end: String): JsonObject {
        check(HealthPermission.getReadPermission(OxygenSaturationRecord::class))
        val (from, to) = dateRange(start, end)
        val records = readAll(OxygenSaturationRecord::class, from, to)
        val values = records.map { it.percentage.value }
        return buildJsonObject {
            put("count", records.size)
            put("average_percent", if (values.isEmpty()) null else values.average())
            put("min_percent", values.minOrNull()); put("max_percent", values.maxOrNull())
            put("records", buildJsonArray {
                records.forEach { r -> add(buildJsonObject {
                    put("id", r.metadata.id); put("percent", r.percentage.value); put("timestamp", offsetOf(r.time, r.zoneOffset))
                    put("source_application", r.metadata.dataOrigin.packageName)
                }) }
            })
        }
    }

    override suspend fun addOxygenSaturation(entry: OxygenEntry, recordKey: String): WriteResult {
        entry.validate()
        check(HealthPermission.getWritePermission(OxygenSaturationRecord::class))
        val time = parseTime(entry.timestamp)
        val record = OxygenSaturationRecord(time.toInstant(), time.offset, Percentage(entry.percent),
            Metadata.manualEntry(clientRecordId = recordKey, clientRecordVersion = 1))
        checkHealthOperation()
        return WriteResult(record_id = client.insertRecords(listOf(record)).recordIdsList.single(), timestamp = entry.timestamp)
    }

    override suspend fun vitals(start: String, end: String): JsonObject {
        val granted = grantedPermissions()
        val hrvOk = HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class) in granted
        val respOk = HealthPermission.getReadPermission(RespiratoryRateRecord::class) in granted
        if (!hrvOk && !respOk) throw SecurityException("Required Health Connect permission is not granted.")
        check(available()) { "Health Connect is unavailable. Install or update Health Connect." }
        val (from, to) = dateRange(start, end)
        fun stats(values: List<Double>) = buildJsonObject {
            put("count", values.size); put("average", if (values.isEmpty()) null else values.average())
            put("min", values.minOrNull()); put("max", values.maxOrNull())
        }
        return buildJsonObject {
            put("heart_rate_variability_rmssd_ms", if (!hrvOk) JsonNull else
                stats(readAll(HeartRateVariabilityRmssdRecord::class, from, to).map { it.heartRateVariabilityMillis }))
            put("respiratory_rate_per_min", if (!respOk) JsonNull else
                stats(readAll(RespiratoryRateRecord::class, from, to).map { it.rate }))
        }
    }

    override suspend fun activityDetail(start: String, end: String): JsonObject {
        val granted = grantedPermissions()
        fun ok(type: KClass<out Record>) = HealthPermission.getReadPermission(type) in granted
        val distance = ok(DistanceRecord::class); val floors = ok(FloorsClimbedRecord::class)
        val elevation = ok(ElevationGainedRecord::class); val speed = ok(SpeedRecord::class)
        val total = ok(TotalCaloriesBurnedRecord::class); val basal = ok(BasalMetabolicRateRecord::class)
        if (!distance && !floors && !elevation && !speed && !total && !basal) throw SecurityException("Required Health Connect permission is not granted.")
        check(available()) { "Health Connect is unavailable. Install or update Health Connect." }
        val (from, to) = dateRange(start, end)
        val metrics = buildSet<androidx.health.connect.client.aggregate.AggregateMetric<*>> {
            if (distance) add(DistanceRecord.DISTANCE_TOTAL)
            if (floors) add(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL)
            if (elevation) add(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)
            if (speed) { add(SpeedRecord.SPEED_AVG); add(SpeedRecord.SPEED_MAX) }
            if (total) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            if (basal) add(BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL)
        }
        val result = aggregate(metrics, from, to)
        return buildJsonObject {
            put("distance_m", if (distance) result[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0 else null)
            put("floors_climbed", if (floors) result[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL] ?: 0.0 else null)
            put("elevation_gained_m", if (elevation) result[ElevationGainedRecord.ELEVATION_GAINED_TOTAL]?.inMeters ?: 0.0 else null)
            put("speed_avg_mps", if (speed) result[SpeedRecord.SPEED_AVG]?.inMetersPerSecond else null)
            put("speed_max_mps", if (speed) result[SpeedRecord.SPEED_MAX]?.inMetersPerSecond else null)
            put("total_calories_kcal", if (total) result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories else null)
            put("basal_calories_kcal", if (basal) result[BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL]?.inKilocalories else null)
            put("sources", sources(result))
        }
    }

    override suspend fun mindfulness(start: String, end: String): JsonObject {
        check(HealthPermission.getReadPermission(MindfulnessSessionRecord::class))
        val (from, to) = dateRange(start, end)
        val records = readAll(MindfulnessSessionRecord::class, from, to)
        return buildJsonObject {
            put("total_minutes", records.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() })
            put("records", buildJsonArray {
                records.forEach { r -> add(buildJsonObject {
                    put("id", r.metadata.id); put("session_type", mindfulnessName(r.mindfulnessSessionType))
                    put("title", r.title); put("notes", r.notes)
                    put("start_time", offsetOf(r.startTime, r.startZoneOffset)); put("end_time", offsetOf(r.endTime, r.endZoneOffset))
                    put("duration_minutes", Duration.between(r.startTime, r.endTime).toMinutes())
                    put("source_application", r.metadata.dataOrigin.packageName)
                }) }
            })
        }
    }

    override suspend fun addMindfulness(entry: MindfulnessEntry, recordKey: String): WriteResult {
        entry.validate()
        check(HealthPermission.getWritePermission(MindfulnessSessionRecord::class))
        val start = parseTime(entry.start_time)
        val end = parseTime(entry.end_time)
        val record = MindfulnessSessionRecord(start.toInstant(), start.offset, end.toInstant(), end.offset,
            Metadata.manualEntry(clientRecordId = recordKey, clientRecordVersion = 1), mindfulnessType(entry.session_type),
            entry.title, entry.notes)
        checkHealthOperation()
        return WriteResult(record_id = client.insertRecords(listOf(record)).recordIdsList.single(), timestamp = entry.start_time)
    }

    private fun mindfulnessType(name: String) = when (name) {
        "meditation" -> MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MEDITATION
        "breathing" -> MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_BREATHING
        "music" -> MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MUSIC
        "movement" -> MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MOVEMENT
        "unguided" -> MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_UNGUIDED
        else -> MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_UNKNOWN
    }

    private fun mindfulnessName(type: Int) = when (type) {
        MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MEDITATION -> "meditation"
        MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_BREATHING -> "breathing"
        MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MUSIC -> "music"
        MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MOVEMENT -> "movement"
        MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_UNGUIDED -> "unguided"
        else -> "other"
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
            "body" to listOf(BodyFatRecord::class, LeanBodyMassRecord::class, HeightRecord::class, BoneMassRecord::class, BodyWaterMassRecord::class),
            "oxygen" to listOf(OxygenSaturationRecord::class),
            "vitals" to listOf(HeartRateVariabilityRmssdRecord::class, RespiratoryRateRecord::class),
            "activity" to listOf(DistanceRecord::class, FloorsClimbedRecord::class, ElevationGainedRecord::class, SpeedRecord::class,
                TotalCaloriesBurnedRecord::class, BasalMetabolicRateRecord::class),
            "mindfulness" to listOf(MindfulnessSessionRecord::class),
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
        is HeightRecord -> record.time
        is BoneMassRecord -> record.time
        is BodyWaterMassRecord -> record.time
        is OxygenSaturationRecord -> record.time
        is HeartRateVariabilityRmssdRecord -> record.time
        is RespiratoryRateRecord -> record.time
        is DistanceRecord -> record.endTime
        is FloorsClimbedRecord -> record.endTime
        is ElevationGainedRecord -> record.endTime
        is TotalCaloriesBurnedRecord -> record.endTime
        is MindfulnessSessionRecord -> record.endTime
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
            record.metadata.clientRecordId?.startsWith("androidmcp-test:") == true) { "Only Android MCP Bridge test records can be deleted here." }
        checkHealthOperation()
        client.deleteRecords(NutritionRecord::class, listOf(id), emptyList())
    }

    override suspend fun deleteNutrition(id: String) {
        require(id.isNotBlank() && id.length <= 200) { "Invalid record ID." }
        check(HealthPermission.getReadPermission(NutritionRecord::class))
        check(HealthPermission.getWritePermission(NutritionRecord::class))
        checkHealthOperation()
        val record = client.readRecord(NutritionRecord::class, id).record
        require(record.metadata.dataOrigin.packageName == context.packageName) { "Only Android MCP Bridge-owned records can be deleted." }
        checkHealthOperation()
        client.deleteRecords(NutritionRecord::class, listOf(id), emptyList())
    }

    override suspend fun deleteWeight(id: String) {
        require(id.isNotBlank() && id.length <= 200) { "Invalid record ID." }
        check(HealthPermission.getReadPermission(WeightRecord::class))
        check(HealthPermission.getWritePermission(WeightRecord::class))
        checkHealthOperation()
        val record = client.readRecord(WeightRecord::class, id).record
        require(record.metadata.dataOrigin.packageName == context.packageName) { "Only Android MCP Bridge-owned records can be deleted." }
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
            HealthPermission.getReadPermission(HeightRecord::class),
            HealthPermission.getWritePermission(HeightRecord::class),
            HealthPermission.getReadPermission(BoneMassRecord::class),
            HealthPermission.getWritePermission(BoneMassRecord::class),
            HealthPermission.getReadPermission(BodyWaterMassRecord::class),
            HealthPermission.getWritePermission(BodyWaterMassRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getWritePermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(RespiratoryRateRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(FloorsClimbedRecord::class),
            HealthPermission.getReadPermission(ElevationGainedRecord::class),
            HealthPermission.getReadPermission(SpeedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
            HealthPermission.getReadPermission(MindfulnessSessionRecord::class),
            HealthPermission.getWritePermission(MindfulnessSessionRecord::class),
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
