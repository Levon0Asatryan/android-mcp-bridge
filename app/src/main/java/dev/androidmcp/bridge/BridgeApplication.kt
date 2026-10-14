package dev.androidmcp.bridge

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.PowerManager
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import dev.androidmcp.bridge.api.HealthActions
import dev.androidmcp.bridge.device.*
import dev.androidmcp.bridge.relay.DeviceConditions
import dev.androidmcp.bridge.relay.KeystoreDeviceIdentity
import dev.androidmcp.bridge.relay.RelayException
import dev.androidmcp.bridge.relay.RelayManager
import dev.androidmcp.bridge.relay.canonicalOrigin
import dev.androidmcp.bridge.relay.pairDevice
import dev.androidmcp.bridge.relay.relayHttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import dev.androidmcp.bridge.data.*
import dev.androidmcp.bridge.health.HealthConnectManager
import dev.androidmcp.bridge.security.*
import kotlinx.coroutines.*

class BridgeApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val health by lazy { HealthConnectManager(this) }
    val database by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "androidmcp.db").addMigrations(
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE clients ADD COLUMN expiresAt INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE clients SET verifier = NULL")
                }
            },
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Existing tokens have no fingerprint, so they stay local-only until regenerated.
                    db.execSQL("ALTER TABLE clients ADD COLUMN remoteFingerprint TEXT DEFAULT NULL")
                    db.execSQL("CREATE TABLE IF NOT EXISTS security_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "timestamp INTEGER NOT NULL, source TEXT NOT NULL, type TEXT NOT NULL, detail TEXT, count INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS instance_event_cursor (scope TEXT NOT NULL PRIMARY KEY, lastSequence INTEGER NOT NULL)")
                }
            },
            MIGRATION_3_4,
        ).build()
    }
    val settings by lazy { SettingsRepository(this) }
    val clients by lazy { ClientManager(database.dao(), TokenManager(KeystoreProtector())) { settings.settings.first().pairingGeneration } }
    val linkApproval = LinkApproval { securityAlert("Open Android MCP Bridge to review a ChatGPT connection request.") }
    val audit by lazy { AuditLogger(database.dao(), settings) }
    val phoneApproval = PhoneApproval()
    private val visibility = VisibilityTracker()
    fun phoneUnlocked(): Boolean {
        val keyguard = getSystemService(KeyguardManager::class.java)
        return keyguard.isDeviceSecure && !keyguard.isDeviceLocked
    }
    fun powerSaving(): Boolean = getSystemService(PowerManager::class.java).isPowerSaveMode
    val relay: RelayManager by lazy {
        val deviceModules = listOf(
            ClockModule(AndroidClock(this, visibility::visible)),
            NotifyModule(AndroidNotifier(this), AndroidSpeaker(this)),
        )
        val actions = HealthActions(health, clients, audit, phoneApproval, ::phoneUnlocked, ::powerSaving, deviceModules) {
            relay.state.value is dev.androidmcp.bridge.relay.RelayState.Connected
        }
        RelayManager(scope, settings.settings, clients, actions, DeviceConditions(this, phoneApproval::invalidate),
            KeystoreDeviceIdentity::getOrCreate, ::recordInstanceEvents, linkApproval = linkApproval)
    }

    /**
     * Pairs with the owner's instance. The bootstrap secret is used only for the two pairing requests, then zeroed;
     * the stored state is the public origin, pairing generation and public device-key thumbprint.
     */
    suspend fun pair(input: String, bootstrap: CharArray) {
        try {
            val origin = canonicalOrigin(input)
                ?: throw RelayException(null, "Enter the instance origin only, for example https://bridge.example.com")
            if (!getSystemService(KeyguardManager::class.java).isDeviceSecure)
                throw RelayException(null, "Set a PIN, password or pattern screen lock before pairing.")
            relay.stop()
            val signer = KeystoreDeviceIdentity.getOrCreate()
            val result = try { relayHttpClient().use { pairDevice(it, origin, bootstrap, signer) } }
                catch (e: java.io.IOException) { throw RelayException(null, "Could not reach the instance (${e.javaClass.simpleName}).") }
            settings.setPairing(origin, result.pairingGeneration, result.deviceKid)
            recordLocalEvent("paired", null, "This phone was paired with your private instance.")
        } finally { bootstrap.fill('\u0000') }
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(visibility)
        scope.launch {
            kotlinx.coroutines.flow.combine(relay.state, database.dao().clients()) { state, list ->
                state to list.count { it.enabled && it.verifier != null }
            }.distinctUntilChanged().collect { (state, apps) -> dev.androidmcp.bridge.widget.PhoneAccessWidget.refresh(this@BridgeApplication, state, apps) }
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(SECURITY_CHANNEL, "Security alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    /** Stores bounded backend security metadata (no tokens, IPs or health identifiers) and alerts on notable events. */
    private suspend fun recordInstanceEvents(events: List<JsonObject>) {
        val dao = database.dao()
        val paired = settings.settings.first()
        val eventScope = "${paired.instanceUrl}|${paired.pairingGeneration}|${paired.deviceKid}"
        val notable = mutableListOf<String>()
        for (event in events) {
            val type = event.getValue("type").jsonPrimitive.content
            val detail = event["detail"]?.jsonPrimitive?.contentOrNull
            val count = event.getValue("count").jsonPrimitive.int
            val sequence = event.getValue("seq").jsonPrimitive.long
            val timestamp = event.getValue("at").jsonPrimitive.long
            if (dao.recordInstanceEvent(eventScope, sequence,
                    SecurityEventEntity(timestamp = timestamp, source = "instance", type = type, detail = detail, count = count))) {
                describeSecurityEvent(type, detail, count)?.let(notable::add)
            }
        }
        dao.pruneSecurityEvents()
        if (notable.isNotEmpty()) securityAlert(notable.joinToString("\n"))
    }

    suspend fun recordLocalEvent(type: String, detail: String? = null, alert: String? = null) {
        database.dao().addSecurityEvent(SecurityEventEntity(source = "phone", type = type, detail = detail))
        database.dao().pruneSecurityEvents()
        alert?.let(::securityAlert)
    }

    fun securityAlert(text: String) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w("AndroidMcpBridge", "Security notification permission is denied; events remain available in the app.")
            return
        }
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, SECURITY_CHANNEL).setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle("Android MCP Bridge security").setContentText(text.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(SECURITY_NOTIFICATION, notification)
    }

    companion object {
        const val SECURITY_CHANNEL = "security"
        private const val SECURITY_NOTIFICATION = 7
    }
}

fun describeSecurityEvent(type: String, detail: String?, count: Int): String? = when (type) {
    "device_connected" -> if (detail == "replaced_previous") "A new phone session replaced an existing one." else "An authenticated phone session connected."
    "client_first_use" -> "An application token was used through the instance for the first time."
    "auth_failures" -> "$count rejected application credential attempt(s) at the instance."
    "challenge_flood" -> "The instance limited a burst of device challenge requests."
    "device_replacement_rejected" -> "The instance rejected an attempt to pair another device."
    "pairing_reset" -> "The instance pairing was reset by its operator."
    else -> null
}
