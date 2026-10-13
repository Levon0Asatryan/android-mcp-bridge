package dev.healthbridge.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "clients")
data class ClientEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long? = null,
    val permissions: String = "",
    val verifier: String? = null,
    @ColumnInfo(defaultValue = "0") val expiresAt: Long = 0,
    // SHA-256 of the raw 256-bit token, registered with the paired instance. Not usable as a credential.
    @ColumnInfo(defaultValue = "NULL") val remoteFingerprint: String? = null,
    // OAuth-linked (MCP) clients only: the predefined OAuth client, the scopes approved on this phone, and the
    // grant version and pairing generation every relayed request must repeat exactly.
    @ColumnInfo(defaultValue = "NULL") val oauthClientId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val oauthScopes: String? = null,
    @ColumnInfo(defaultValue = "0") val grantVersion: Int = 0,
    @ColumnInfo(defaultValue = "0") val pairingGeneration: Int = 0,
)

@Entity(tableName = "security_events")
data class SecurityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String,
    val type: String,
    val detail: String? = null,
    val count: Int = 1,
)

@Entity(tableName = "instance_event_cursor")
data class InstanceEventCursor(@PrimaryKey val scope: String, val lastSequence: Long)

@Entity(tableName = "audit")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val clientId: String?,
    val clientName: String,
    val operation: String,
    val result: String,
    val errorCategory: String? = null,
)

@Entity(tableName = "write_receipts", primaryKeys = ["clientId", "operation", "keyHash"])
data class WriteReceipt(
    val clientId: String, val operation: String, val keyHash: String,
    val payloadHash: String, val recordKey: String, val recordId: String? = null,
)

interface ClientStore {
    suspend fun all(): List<ClientEntity>
    suspend fun find(id: String): ClientEntity?
    suspend fun save(client: ClientEntity)
    suspend fun delete(id: String)
    suspend fun receipt(clientId: String, operation: String, keyHash: String): WriteReceipt?
    suspend fun saveReceipt(receipt: WriteReceipt)
}

@Dao
interface BridgeDao : ClientStore {
    @Query("SELECT * FROM clients ORDER BY createdAt DESC") fun clients(): Flow<List<ClientEntity>>
    @Query("SELECT * FROM clients") override suspend fun all(): List<ClientEntity>
    @Query("SELECT * FROM clients WHERE id = :id") override suspend fun find(id: String): ClientEntity?
    @Upsert override suspend fun save(client: ClientEntity)
    @Query("DELETE FROM clients WHERE id = :id") override suspend fun delete(id: String)
    @Query("SELECT * FROM write_receipts WHERE clientId=:clientId AND operation=:operation AND keyHash=:keyHash")
    override suspend fun receipt(clientId: String, operation: String, keyHash: String): WriteReceipt?
    @Upsert override suspend fun saveReceipt(receipt: WriteReceipt)
    @Query("SELECT * FROM audit ORDER BY timestamp DESC LIMIT 1000") fun audit(): Flow<List<AuditLogEntity>>
    @Insert suspend fun log(entry: AuditLogEntity)
    @Query("DELETE FROM audit WHERE timestamp < :cutoff OR id NOT IN (SELECT id FROM audit ORDER BY timestamp DESC LIMIT 1000)")
    suspend fun prune(cutoff: Long)
    @Query("DELETE FROM audit") suspend fun clearAudit()
    @Query("SELECT * FROM security_events ORDER BY timestamp DESC, id DESC LIMIT 200") fun securityEvents(): Flow<List<SecurityEventEntity>>
    @Insert suspend fun addSecurityEvent(event: SecurityEventEntity)
    @Query("DELETE FROM security_events WHERE id NOT IN (SELECT id FROM security_events ORDER BY timestamp DESC, id DESC LIMIT 200)")
    suspend fun pruneSecurityEvents()
    @Query("SELECT lastSequence FROM instance_event_cursor WHERE scope = :scope")
    suspend fun instanceEventSequence(scope: String): Long?
    @Upsert suspend fun saveInstanceEventCursor(cursor: InstanceEventCursor)
    @Transaction
    suspend fun recordInstanceEvent(scope: String, sequence: Long, event: SecurityEventEntity): Boolean {
        if (sequence <= (instanceEventSequence(scope) ?: 0)) return false
        addSecurityEvent(event)
        saveInstanceEventCursor(InstanceEventCursor(scope, sequence))
        return true
    }
}

@Database(entities = [ClientEntity::class, AuditLogEntity::class, WriteReceipt::class, SecurityEventEntity::class, InstanceEventCursor::class],
    version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() { abstract fun dao(): BridgeDao }

/** Adds OAuth link columns. Existing rows stay token clients (oauthClientId NULL). */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE clients ADD COLUMN oauthClientId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE clients ADD COLUMN oauthScopes TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE clients ADD COLUMN grantVersion INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE clients ADD COLUMN pairingGeneration INTEGER NOT NULL DEFAULT 0")
    }
}
