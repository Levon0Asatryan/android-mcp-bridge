package dev.healthbridge.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import java.time.Instant
import java.time.ZoneId

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
        val record = NutritionRecord(
            startTime = time.toInstant(), startZoneOffset = time.offset,
            endTime = time.toInstant().plusSeconds(1), endZoneOffset = time.offset,
            metadata = Metadata.manualEntry(clientRecordId = recordKey, clientRecordVersion = 1),
            name = entry.meal_name, mealType = mealType(entry.meal_type),
            energy = Energy.kilocalories(entry.calories_kcal),
            protein = Mass.grams(entry.protein_g), totalCarbohydrate = Mass.grams(entry.carbs_g),
            totalFat = Mass.grams(entry.fat_g), sugar = entry.sugar_g?.let(Mass::grams),
            dietaryFiber = entry.fiber_g?.let(Mass::grams), sodium = entry.sodium_mg?.let(Mass::milligrams),
        )
        checkHealthOperation()
        val id = client.insertRecords(listOf(record)).recordIdsList.single()
        return WriteResult(record_id = id, timestamp = entry.timestamp)
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
        )
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
