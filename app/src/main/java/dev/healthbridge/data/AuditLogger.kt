package dev.healthbridge.data

import kotlinx.coroutines.flow.first

interface AuditSink {
    suspend fun record(client: ClientEntity?, operation: String, result: String, category: String? = null)
}
class AuditLogger(private val dao: BridgeDao, private val settings: SettingsRepository) : AuditSink {
    override suspend fun record(client: ClientEntity?, operation: String, result: String, category: String?) {
        dao.log(AuditLogEntity(clientId = client?.id, clientName = client?.name ?: "Unknown client",
            operation = operation, result = result, errorCategory = category))
        dao.prune(System.currentTimeMillis() - settings.settings.first().retentionDays * 86400000L)
    }
}
