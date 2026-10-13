package dev.healthbridge

import dev.healthbridge.health.importMeal
import org.junit.Assert.*
import org.junit.Test

class MealImportTest {
    @Test fun importsFencedJsonAndUsesLocalTimeOnlyWhenOmitted() {
        val json = """{"meal_name":"Lunch","meal_type":"lunch","calories_kcal":400,"protein_g":20,"carbs_g":40,"fat_g":15}"""
        val meal = importMeal("```json\n$json\n```")
        assertEquals("Lunch", meal.meal_name)
        assertTrue(meal.timestamp.isNotBlank())
    }
    @Test fun rejectsUnknownFieldsAndOversizedPayloads() {
        assertThrows(IllegalArgumentException::class.java) { importMeal("x".repeat(32769)) }
        assertThrows(IllegalArgumentException::class.java) { importMeal("""{"nutrients":{"energy":10}}""") }
    }
}
