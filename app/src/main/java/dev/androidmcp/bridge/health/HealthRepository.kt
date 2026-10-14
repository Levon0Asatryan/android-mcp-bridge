package dev.androidmcp.bridge.health

import kotlinx.serialization.json.JsonObject

interface HealthRepository {
    fun available(): Boolean
    suspend fun grantedPermissions(): Set<String>
    suspend fun nutrition(start: String, end: String): List<NutritionData>
    suspend fun addNutrition(entry: NutritionEntry, recordKey: String): WriteResult
    suspend fun weight(start: String, end: String): List<WeightData>
    suspend fun addWeight(entry: WeightEntry, recordKey: String): WriteResult
    suspend fun steps(start: String, end: String): Long
    suspend fun deleteNutrition(id: String)
    suspend fun deleteWeight(id: String)

    // Extended data types. Defaults keep older implementations (and test fakes) compiling.
    suspend fun updateNutrition(id: String, changes: NutritionChanges): WriteResult = unsupported()
    suspend fun stepSources(start: String, end: String): List<String> = emptyList()
    suspend fun hydration(start: String, end: String): JsonObject = unsupported()
    suspend fun addHydration(entry: HydrationEntry, recordKey: String): WriteResult = unsupported()
    suspend fun updateHydration(id: String, changes: HydrationChanges): WriteResult = unsupported()
    suspend fun deleteHydration(id: String) { unsupported() }
    suspend fun sleep(start: String, end: String): JsonObject = unsupported()
    suspend fun addSleep(entry: SleepEntry, recordKey: String): WriteResult = unsupported()
    suspend fun workouts(start: String, end: String): JsonObject = unsupported()
    suspend fun addWorkout(entry: WorkoutEntry, recordKey: String): WriteResult = unsupported()
    suspend fun heartRate(start: String, end: String): JsonObject = unsupported()
    suspend fun activeCalories(start: String, end: String): JsonObject = unsupported()
    suspend fun bodyMeasurements(start: String, end: String): JsonObject = unsupported()
    suspend fun addBodyMeasurement(entry: BodyMeasurementEntry, recordKey: String): WriteResult = unsupported()

    suspend fun oxygenSaturation(start: String, end: String): JsonObject = unsupported()
    suspend fun addOxygenSaturation(entry: OxygenEntry, recordKey: String): WriteResult = unsupported()
    suspend fun vitals(start: String, end: String): JsonObject = unsupported()
    suspend fun activityDetail(start: String, end: String): JsonObject = unsupported()
    suspend fun mindfulness(start: String, end: String): JsonObject = unsupported()
    suspend fun addMindfulness(entry: MindfulnessEntry, recordKey: String): WriteResult = unsupported()

    /** Latest writer activity per data type over the last 30 days, limited to [types] the caller may see. */
    suspend fun freshness(types: Set<String>): JsonObject = unsupported()
}

private fun unsupported(): Nothing = throw IllegalStateException("Not supported by this health repository.")
