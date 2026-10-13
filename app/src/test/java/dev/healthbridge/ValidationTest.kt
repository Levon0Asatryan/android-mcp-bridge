package dev.healthbridge

import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import dev.healthbridge.health.*
import org.junit.Assert.*
import org.junit.Test

class ValidationTest {
    private fun meal() = NutritionEntry("Dinner", "dinner", "2026-01-01T20:00:00+04:00", 540.0, 68.0, 45.0, 9.0)
    @Test fun validatesMealAndUnits() {
        meal().validate()
        assertEquals(540.0, Energy.kilocalories(540.0).inKilocalories, 0.0001)
        assertEquals(0.5, Mass.milligrams(500.0).inGrams, 0.0001)
        assertEquals(70000.0, Mass.kilograms(70.0).inGrams, 0.0001)
    }
    @Test fun rejectsInvalidNutrition() {
        assertThrows(IllegalArgumentException::class.java) { meal().copy(protein_g = -1.0).validate() }
        assertThrows(IllegalArgumentException::class.java) { meal().copy(fat_g = Double.NaN).validate() }
        assertThrows(IllegalArgumentException::class.java) { meal().copy(sodium_mg = 100001.0).validate() }
        assertThrows(IllegalArgumentException::class.java) { meal().copy(meal_type = "tea").validate() }
        assertThrows(IllegalArgumentException::class.java) { meal().copy(idempotency_key = "").validate() }
        assertThrows(IllegalArgumentException::class.java) { meal().copy(timestamp = "2026-01-01T20:00:00").validate() }
    }
    @Test fun validatesRangesAndWeight() {
        assertThrows(IllegalArgumentException::class.java) { dateRange("2026-01-02T00:00:00Z", "2026-01-01T00:00:00Z") }
        assertThrows(IllegalArgumentException::class.java) { dateRange("2024-01-01T00:00:00Z", "2026-01-01T00:00:00Z") }
        assertThrows(IllegalArgumentException::class.java) { WeightEntry(0.0, "2026-01-01T00:00:00Z").validate() }
        WeightEntry(70.0, "2026-01-01T00:00:00Z").validate()
    }
}
