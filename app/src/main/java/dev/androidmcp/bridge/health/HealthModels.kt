package dev.androidmcp.bridge.health

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** Detail nutrients beyond calories/protein/carbs/fat; the unit is part of the key (_g, _mg, _mcg). */
val DETAIL_NUTRIENTS = listOf(
    "saturated_fat_g", "unsaturated_fat_g", "monounsaturated_fat_g", "polyunsaturated_fat_g", "trans_fat_g", "cholesterol_mg",
    "potassium_mg", "calcium_mg", "iron_mg", "magnesium_mg", "zinc_mg", "phosphorus_mg", "copper_mg", "manganese_mg", "chloride_mg",
    "selenium_mcg", "chromium_mcg", "molybdenum_mcg", "iodine_mcg",
    "vitamin_a_mcg", "vitamin_b6_mg", "vitamin_b12_mcg", "vitamin_c_mg", "vitamin_d_mcg", "vitamin_e_mg", "vitamin_k_mcg",
    "thiamin_mg", "riboflavin_mg", "niacin_mg", "folate_mcg", "folic_acid_mcg", "biotin_mcg", "pantothenic_acid_mg", "caffeine_mg",
)
private val DETAIL_SET = DETAIL_NUTRIENTS.toSet()

fun validateNutrients(values: Map<String, Double>) = values.forEach { (key, value) ->
    require(key in DETAIL_SET) { "Unsupported nutrient $key." }
    require(value.isFinite() && value in 0.0..100000.0) { "$key must be 0-100000." }
}

/** Moves flat nutrient keys of a tool call into `extra` so they decode into the typed models. */
fun splitNutrients(args: JsonObject): Pair<JsonObject, Map<String, Double>> {
    val extra = args.filterKeys { it in DETAIL_SET }.mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { key to (it.doubleOrNull ?: throw IllegalArgumentException("$key must be a number.")) }
    }.toMap()
    return JsonObject(args.filterKeys { it !in DETAIL_SET }) to extra
}

@Serializable
data class NutritionEntry(
    val meal_name: String,
    val meal_type: String,
    val timestamp: String,
    val calories_kcal: Double,
    val protein_g: Double,
    val carbs_g: Double,
    val fat_g: Double,
    val sugar_g: Double? = null,
    val fiber_g: Double? = null,
    val sodium_mg: Double? = null,
    val idempotency_key: String? = null,
    val extra: Map<String, Double> = emptyMap(),
) {
    fun validate() {
        validateNutrients(extra)
        require(meal_name.isNotBlank() && meal_name.length <= 200) { "Meal name must contain 1-200 characters." }
        require(meal_type in setOf("breakfast", "lunch", "dinner", "snack", "unknown")) { "Unsupported meal type." }
        parseTime(timestamp)
        require(calories_kcal.isFinite() && calories_kcal in 0.0..100000.0) { "Calories must be 0-100000 kcal." }
        listOf(protein_g, carbs_g, fat_g, sugar_g, fiber_g).filterNotNull().forEach {
            require(it.isFinite() && it in 0.0..100000.0) { "Nutrients must be 0-100000 grams." }
        }
        sodium_mg?.let { require(it.isFinite() && it in 0.0..100000.0) { "Sodium must be 0-100000 mg." } }
        validateKey(idempotency_key)
    }
}

@Serializable
data class WeightEntry(val weight_kg: Double, val timestamp: String, val idempotency_key: String? = null) {
    fun validate() {
        require(weight_kg.isFinite() && weight_kg > 0 && weight_kg <= 1000) { "Weight must be greater than 0 and at most 1000 kg." }
        parseTime(timestamp)
        validateKey(idempotency_key)
    }
}

val EXERCISE_TYPE_NAMES = setOf(
    "strength_training", "running", "walking", "cycling", "swimming", "yoga", "pilates", "hiit", "hiking", "rowing",
    "elliptical", "stair_climbing", "stretching", "dancing", "calisthenics", "other",
)

private fun validateSpan(start: String, end: String) {
    val from = parseTime(start).toInstant()
    val to = parseTime(end).toInstant()
    require(from < to) { "start_time must precede end_time." }
    require(to.epochSecond - from.epochSecond <= 86_400) { "A session can last at most 24 hours." }
}

@Serializable
data class NutritionChanges(
    val meal_name: String? = null, val meal_type: String? = null, val timestamp: String? = null,
    val calories_kcal: Double? = null, val protein_g: Double? = null, val carbs_g: Double? = null, val fat_g: Double? = null,
    val sugar_g: Double? = null, val fiber_g: Double? = null, val sodium_mg: Double? = null,
    val extra: Map<String, Double> = emptyMap(),
) {
    fun isEmpty() = extra.isEmpty() && listOf(meal_name, meal_type, timestamp, calories_kcal, protein_g, carbs_g, fat_g, sugar_g, fiber_g, sodium_mg).all { it == null }
}

@Serializable
data class HydrationEntry(val volume_ml: Double, val timestamp: String, val idempotency_key: String? = null) {
    fun validate() {
        require(volume_ml.isFinite() && volume_ml > 0 && volume_ml <= 10_000) { "Volume must be greater than 0 and at most 10000 ml." }
        parseTime(timestamp)
        validateKey(idempotency_key)
    }
}

@Serializable
data class HydrationChanges(val volume_ml: Double? = null, val timestamp: String? = null) {
    fun isEmpty() = volume_ml == null && timestamp == null
    fun validate() {
        require(!isEmpty()) { "Provide at least one change." }
        volume_ml?.let { require(it.isFinite() && it > 0 && it <= 10_000) { "Volume must be greater than 0 and at most 10000 ml." } }
        timestamp?.let { parseTime(it) }
    }
}

@Serializable
data class SleepEntry(val start_time: String, val end_time: String, val title: String? = null, val idempotency_key: String? = null) {
    fun validate() {
        validateSpan(start_time, end_time)
        title?.let { require(it.isNotBlank() && it.length <= 200) { "Title must contain 1-200 characters." } }
        validateKey(idempotency_key)
    }
}

@Serializable
data class WorkoutEntry(
    val exercise_type: String, val start_time: String, val end_time: String,
    val title: String? = null, val notes: String? = null, val idempotency_key: String? = null,
) {
    fun validate() {
        require(exercise_type in EXERCISE_TYPE_NAMES) { "Unsupported exercise type." }
        validateSpan(start_time, end_time)
        title?.let { require(it.isNotBlank() && it.length <= 200) { "Title must contain 1-200 characters." } }
        notes?.let { require(it.isNotBlank() && it.length <= 1000) { "Notes must contain 1-1000 characters." } }
        validateKey(idempotency_key)
    }
}

@Serializable
data class BodyMeasurementEntry(
    val timestamp: String, val body_fat_percent: Double? = null, val lean_body_mass_kg: Double? = null,
    val idempotency_key: String? = null,
    val height_cm: Double? = null, val bone_mass_kg: Double? = null, val body_water_mass_kg: Double? = null,
) {
    fun validate() {
        require(listOf(body_fat_percent, lean_body_mass_kg, height_cm, bone_mass_kg, body_water_mass_kg).any { it != null }) {
            "Provide at least one body composition value."
        }
        body_fat_percent?.let { require(it.isFinite() && it > 0 && it <= 100) { "Body fat must be greater than 0 and at most 100." } }
        lean_body_mass_kg?.let { require(it.isFinite() && it > 0 && it <= 1000) { "Lean body mass must be greater than 0 and at most 1000 kg." } }
        height_cm?.let { require(it.isFinite() && it > 0 && it <= 300) { "Height must be greater than 0 and at most 300 cm." } }
        bone_mass_kg?.let { require(it.isFinite() && it > 0 && it <= 100) { "Bone mass must be greater than 0 and at most 100 kg." } }
        body_water_mass_kg?.let { require(it.isFinite() && it > 0 && it <= 500) { "Body water mass must be greater than 0 and at most 500 kg." } }
        parseTime(timestamp)
        validateKey(idempotency_key)
    }
}

val MINDFULNESS_TYPE_NAMES = setOf("meditation", "breathing", "music", "movement", "unguided", "other")

@Serializable
data class OxygenEntry(val percent: Double, val timestamp: String, val idempotency_key: String? = null) {
    fun validate() {
        require(percent.isFinite() && percent > 0 && percent <= 100) { "Oxygen saturation must be greater than 0 and at most 100 percent." }
        parseTime(timestamp)
        validateKey(idempotency_key)
    }
}

@Serializable
data class MindfulnessEntry(
    val session_type: String, val start_time: String, val end_time: String,
    val title: String? = null, val notes: String? = null, val idempotency_key: String? = null,
) {
    fun validate() {
        require(session_type in MINDFULNESS_TYPE_NAMES) { "Unsupported session type." }
        validateSpan(start_time, end_time)
        title?.let { require(it.isNotBlank() && it.length <= 200) { "Title must contain 1-200 characters." } }
        notes?.let { require(it.isNotBlank() && it.length <= 1000) { "Notes must contain 1-1000 characters." } }
        validateKey(idempotency_key)
    }
}

@Serializable
data class NutritionData(
    val id: String, val meal_name: String?, val meal_type: String,
    val start_time: String, val end_time: String,
    val calories_kcal: Double?, val protein_g: Double?, val carbs_g: Double?,
    val fat_g: Double?, val sugar_g: Double?, val fiber_g: Double?, val sodium_mg: Double?,
    val source_application: String,
    val nutrients: Map<String, Double> = emptyMap(),
)
@Serializable
data class WeightData(val id: String, val weight_kg: Double, val timestamp: String, val source_application: String)
@Serializable
data class WriteResult(
    val success: Boolean = true, val record_id: String, val timestamp: String,
    val source_application: String = "dev.androidmcp.bridge",
    val health_connect_inserted: Boolean = true,
    val samsung_health_visibility: String = "not_verified",
)

fun parseTime(value: String): OffsetDateTime {
    require(value.length <= 64) { "Timestamp is too long." }
    val time = try { OffsetDateTime.parse(value) }
    catch (e: DateTimeParseException) { throw IllegalArgumentException("Use an ISO 8601 timestamp with an explicit UTC offset.", e) }
    require(!time.toInstant().isAfter(Instant.now().plusSeconds(300))) { "Future timestamps are not accepted." }
    require(time.toInstant().isAfter(Instant.parse("1900-01-01T00:00:00Z"))) { "Timestamp is out of range." }
    return time
}

fun dateRange(start: String, end: String): Pair<Instant, Instant> {
    val first = parseTime(start).toInstant()
    val last = parseTime(end).toInstant()
    require(first < last) { "start_time must precede end_time." }
    require(last.epochSecond - first.epochSecond <= 366L * 86400) { "Query at most 366 days at a time." }
    return first to last
}

fun validateKey(key: String?) {
    require(key == null || (key.isNotBlank() && key.length <= 128)) { "Idempotency key must contain 1-128 characters." }
}

fun deviceTimestamp(): String = OffsetDateTime.now(ZoneId.systemDefault()).toString()
