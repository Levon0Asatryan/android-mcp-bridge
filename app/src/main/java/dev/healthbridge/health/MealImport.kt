package dev.healthbridge.health

import dev.healthbridge.api.bridgeJson
import kotlinx.serialization.json.*

fun importMeal(input: String): NutritionEntry {
    require(input.length <= 32768) { "Paste at most 32 KB of JSON." }
    val trimmed = input.trim()
    val source = if (trimmed.startsWith("```")) {
        require(trimmed.endsWith("```")) { "The JSON code block is incomplete." }
        trimmed.substringAfter('\n').removeSuffix("```").trim()
    } else trimmed
    val json = bridgeJson.parseToJsonElement(source).jsonObject
    val withTime = if ("timestamp" in json) json else JsonObject(json + ("timestamp" to JsonPrimitive(deviceTimestamp())))
    return bridgeJson.decodeFromJsonElement<NutritionEntry>(withTime).also { it.validate() }
}
