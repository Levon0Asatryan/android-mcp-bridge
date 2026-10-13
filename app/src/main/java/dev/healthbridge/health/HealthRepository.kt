package dev.healthbridge.health

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
}
