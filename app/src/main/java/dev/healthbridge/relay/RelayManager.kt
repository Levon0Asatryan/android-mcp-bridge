package dev.healthbridge.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import dev.healthbridge.api.HealthActions
import dev.healthbridge.data.Settings
import dev.healthbridge.security.ClientManager
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random

sealed interface RelayState {
    val label: String
    data object Stopped : RelayState { override val label = "Stopped" }
    data object NotPaired : RelayState { override val label = "Not paired" }
    data object Connecting : RelayState { override val label = "Connecting" }
    data class Connected(val since: Long) : RelayState { override val label = "Connected" }
    data class Waiting(val reason: String, val retryAt: Long) : RelayState { override val label = "Reconnecting" }
    data class Paused(val reason: String) : RelayState { override val label = "Paused" }
    data class Failed(val reason: String) : RelayState { override val label = "Stopped: action needed" }
}

/** Device conditions that pause the outbound connection. Screen lock is handled per request (phone_locked). */
class DeviceConditions(private val context: Context, private val invalidateApproval: () -> Unit = {}) {
    val online = MutableStateFlow(true)
    val powerSaving = MutableStateFlow(false)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (callback != null) return
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        online.value = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        powerSaving.value = power.isPowerSaveMode
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            override fun onLost(network: Network) { online.value = false }
        }.also(connectivity::registerDefaultNetworkCallback)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                powerSaving.value = power.isPowerSaveMode
                if (powerSaving.value || intent?.action == Intent.ACTION_SCREEN_OFF) invalidateApproval()
            }
        }.also {
            val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED).apply { addAction(Intent.ACTION_SCREEN_OFF) }
            context.registerReceiver(it, filter)
        }
    }

    fun stop() {
        callback?.let { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        receiver?.let(context::unregisterReceiver)
        callback = null; receiver = null
    }
}

/** Capped exponential backoff with jitter: 1 s doubling to 5 min; reset after a session stays up for 60 s. */
fun backoffMillis(attempt: Int, random: Random = Random.Default): Long {
    val cap = minOf(300_000L, 1_000L shl attempt.coerceIn(0, 9))
    return (cap / 2) + random.nextLong(cap / 2 + 1)
}

class RelayManager(
    private val scope: CoroutineScope,
    private val settings: Flow<Settings>,
    private val clients: ClientManager,
    private val actions: HealthActions,
    private val conditions: DeviceConditions,
    private val signer: () -> DeviceSigner,
    private val onEvents: suspend (List<JsonObject>) -> Unit,
    private val http: () -> HttpClient = ::relayHttpClient,
    private val linkApproval: dev.healthbridge.security.LinkApproval? = null,
) {
    private val mutable = MutableStateFlow<RelayState>(RelayState.Stopped)
    val state = mutable.asStateFlow()
    private var job: Job? = null
    @Volatile private var session: RelaySession? = null

    init { clients.onCredentialsChanged = { session?.credentialsChanged() } }

    @Synchronized fun start() {
        if (job?.isActive == true) return
        conditions.start()
        job = scope.launch { loop() }
    }

    @Synchronized fun stop() {
        job?.cancel(); job = null
        conditions.stop()
        mutable.value = RelayState.Stopped
    }

    private suspend fun loop() {
        val client = http()
        try {
            var attempt = 0
            while (currentCoroutineContext().isActive) {
                val config = settings.first()
                val origin = config.instanceUrl; val generation = config.pairingGeneration
                if (!config.paired || origin == null || generation == null) { mutable.value = RelayState.NotPaired; return }
                if (!conditions.online.value) {
                    mutable.value = RelayState.Paused("No validated network")
                    conditions.online.first { it }; continue
                }
                if (conditions.powerSaving.value) {
                    mutable.value = RelayState.Paused("Battery saver is on")
                    conditions.powerSaving.first { !it }; continue
                }
                mutable.value = RelayState.Connecting
                var connectedAt = 0L
                val current = RelaySession(client, origin, generation, signer(), clients, actions, onEvents, linkApproval) {
                    connectedAt = System.currentTimeMillis()
                    mutable.value = RelayState.Connected(connectedAt)
                }
                session = current
                var paused = false
                val failure: String? = try {
                    coroutineScope {
                        val sessionScope = this
                        val pause = launch {
                            combine(conditions.online, conditions.powerSaving) { online, saving -> !online || saving }.first { it }
                            paused = true
                            sessionScope.cancel()
                        }
                        try {
                            val reason = current.run()
                            if (reason?.code == RESET_CLOSE_CODE) throw RelayException(null, "The instance pairing was reset. Pair this phone again.", fatal = true)
                            reason?.let { "Closed by instance (${it.code})" } ?: "Connection closed"
                        } finally { pause.cancel() }
                    }
                } catch (e: CancellationException) {
                    if (!paused) throw e
                    currentCoroutineContext().ensureActive()
                    null
                } catch (e: RelayException) {
                    if (e.fatal) { mutable.value = RelayState.Failed(e.message.orEmpty()); return }
                    e.message
                } catch (e: Exception) {
                    "Network error (${e.javaClass.simpleName})"
                } finally { session = null }
                if (failure == null) continue
                if (connectedAt != 0L && System.currentTimeMillis() - connectedAt >= 60_000) attempt = 0
                val wait = backoffMillis(attempt++)
                mutable.value = RelayState.Waiting(failure, System.currentTimeMillis() + wait)
                delay(wait)
            }
        } finally { client.close() }
    }

    companion object { const val RESET_CLOSE_CODE: Short = 4003 }
}
