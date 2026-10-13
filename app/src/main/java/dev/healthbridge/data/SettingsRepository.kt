package dev.healthbridge.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.bridgeSettings by preferencesDataStore("gateway_settings")
data class Settings(
    val theme: String = "System", val retentionDays: Int = 30, val testRecordId: String? = null,
    val instanceUrl: String? = null, val pairingGeneration: Int? = null, val deviceKid: String? = null,
    val pairedAt: Long? = null,
) { val paired get() = instanceUrl != null && pairingGeneration != null && deviceKid != null }

class SettingsRepository(context: Context) {
    private val store = context.bridgeSettings
    private val theme = stringPreferencesKey("theme")
    private val retention = intPreferencesKey("audit_retention_days")
    private val testRecord = stringPreferencesKey("test_record_id")
    private val instance = stringPreferencesKey("instance_url")
    private val generation = intPreferencesKey("pairing_generation")
    private val kid = stringPreferencesKey("device_kid")
    private val pairedAt = longPreferencesKey("paired_at")
    val settings = store.data.map {
        Settings(it[theme] ?: "System", it[retention] ?: 30, it[testRecord],
            it[instance], it[generation], it[kid], it[pairedAt])
    }
    // Stores only the public instance origin and public device-key thumbprint; never the bootstrap credential.
    suspend fun setPairing(origin: String, pairingGeneration: Int, deviceKid: String) {
        store.edit { it[instance] = origin; it[generation] = pairingGeneration; it[kid] = deviceKid; it[pairedAt] = System.currentTimeMillis() }
    }
    suspend fun clearPairing() {
        store.edit { it.remove(instance); it.remove(generation); it.remove(kid); it.remove(pairedAt) }
    }
    suspend fun setTestRecord(id: String?) {
        store.edit { if (id == null) it.remove(testRecord) else it[testRecord] = id }
    }
    suspend fun setTheme(value: String) {
        require(value in setOf("System", "Light", "Dark"))
        store.edit { it[theme] = value }
    }
    suspend fun setRetention(value: Int) {
        require(value in 1..90) { "Retention must be between 1 and 90 days." }
        store.edit { it[retention] = value }
    }
}
