package dev.healthbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.healthbridge.health.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.time.OffsetDateTime

@RunWith(AndroidJUnit4::class)
class HealthConnectDeviceTest {
    @Test fun nutritionWritesAndReadsBackOnPhone() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val health = HealthConnectManager(context)
        assertTrue("Health Connect must be available", health.available())
        assertTrue("Grant all five health permissions in the app", health.grantedPermissions().containsAll(HealthConnectManager.permissions))
        val result = health.addNutrition(
            NutritionEntry("HealthBridge device test", "snack", deviceTimestamp(), 100.0, 2.0, 15.0, 3.0, sodium_mg = 200.0),
            "healthbridge-test:${UUID.randomUUID()}")
        try {
            val record = health.readTestRecord(result.record_id)
            assertEquals(result.record_id, record.metadata.id)
            assertEquals("HealthBridge device test", record.name)
            assertEquals(100.0, record.energy!!.inKilocalories, 0.001)
            assertEquals(200.0, record.sodium!!.inMilligrams, 0.001)
        } finally {
            health.deleteTestRecord(result.record_id)
        }

    }

    @Test fun weightAndStepsWorkOnPhone() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val health = HealthConnectManager(context)
        val now = OffsetDateTime.now()
        val result = health.addWeight(WeightEntry(70.0, now.toString()), "healthbridge-test:${UUID.randomUUID()}")
        try {
            val records = health.weight(now.minusMinutes(1).toString(), now.plusSeconds(2).toString())
            assertTrue(records.any { it.id == result.record_id && it.weight_kg == 70.0 })
            assertTrue(health.steps(now.minusDays(1).toString(), now.toString()) >= 0)
        } finally { health.deleteWeight(result.record_id) }
    }
}
