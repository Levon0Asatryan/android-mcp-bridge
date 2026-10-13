package dev.androidmcp.bridge

import dev.androidmcp.bridge.api.*
import dev.androidmcp.bridge.device.*
import dev.androidmcp.bridge.security.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FakeClock(var delivery: Delivery = Delivery.SENT) : ClockDevice {
    val calls = mutableListOf<String>()
    override fun nextAlarmMillis(): Long? = null
    override fun setAlarm(hour: Int, minute: Int, label: String?, days: List<String>?): Delivery { calls += "alarm $hour:$minute $label $days"; return delivery }
    override fun setTimer(seconds: Int, label: String?): Delivery { calls += "timer $seconds"; return delivery }
    override fun dismissAlarm(mode: String, hour: Int?, minute: Int?, label: String?): Delivery { calls += "dismiss $mode"; return delivery }
    override fun showAlarms(): Delivery { calls += "show"; return delivery }
}

class FakeNotifier : PhoneNotifier {
    val shown = mutableListOf<Triple<String, String, Boolean>>()
    override fun show(title: String, message: String, urgent: Boolean) { shown += Triple(title, message, urgent) }
}

class FakeSpeaker(private val muted: Boolean = false) : PhoneSpeaker {
    val said = mutableListOf<String>()
    override suspend fun speak(message: String) { if (muted) moduleUnavailable("The phone's media volume is muted."); said += message }
}

class DeviceModulesTest {
    private val clock = FakeClock()
    private val notifier = FakeNotifier()
    private suspend fun setup(vararg grants: ClientPermission, speaker: PhoneSpeaker = FakeSpeaker()): Pair<HealthActions, String> {
        val manager = ClientManager(MemoryStore(), testTokens())
        val (_, token) = manager.create("AI", grants.toSet())
        val modules = listOf(ClockModule(clock, settleMillis = 0), NotifyModule(notifier, speaker))
        return HealthActions(ExtendedHealth(), manager, FakeAudit(), modules = modules) { true } to token
    }
    private fun args(vararg f: Pair<String, JsonElement>) = JsonObject(mapOf(*f))
    private fun n(v: Int) = JsonPrimitive(v)

    @Test fun setAlarmNeedsGrantAndIsIdempotent() = runTest {
        val (handler, token) = setup(ClientPermission.CLOCK_ALARM_SET)
        val a = args("hour" to n(7), "minute" to n(30), "label" to text("Gym"), "days" to JsonArray(listOf(text("mon"), text("fri"))), "idempotency_key" to text("k1"))
        val first = handler.execute(token, "set_alarm", a)
        assertEquals("sent_to_clock", first["status"]!!.jsonPrimitive.content)
        val retry = handler.execute(token, "set_alarm", a)
        assertTrue(retry["replayed"]!!.jsonPrimitive.boolean)
        assertEquals(1, clock.calls.size)
        assertEquals("permission_denied", handler.execute(token, "set_timer", args("seconds" to n(60), "idempotency_key" to text("t"))).let { it["error_category"]!!.jsonPrimitive.content })
        val changed = handler.execute(token, "set_alarm", JsonObject(a + ("hour" to n(8))))
        assertEquals("idempotency_conflict", changed["error_category"]!!.jsonPrimitive.content)
    }

    @Test fun invalidAlarmInputIsRejectedAndTapPathIsReported() = runTest {
        val (handler, token) = setup(ClientPermission.CLOCK_ALARM_SET, ClientPermission.CLOCK_TIMER_SET, ClientPermission.CLOCK_ALARM_DISMISS)
        val bad = handler.execute(token, "set_alarm", args("hour" to n(25), "minute" to n(0), "idempotency_key" to text("k")))
        assertEquals("invalid_input", bad["error_category"]!!.jsonPrimitive.content)
        assertEquals("invalid_input", handler.execute(token, "dismiss_alarm", args("mode" to text("all")))["error_category"]!!.jsonPrimitive.content)
        clock.delivery = Delivery.NEEDS_TAP
        val timer = handler.execute(token, "set_timer", args("seconds" to n(600), "idempotency_key" to text("t")))
        assertEquals("awaiting_tap_on_phone", timer["status"]!!.jsonPrimitive.content)
        handler.execute(token, "dismiss_alarm", args("mode" to text("next")))
        assertEquals("dismiss next", clock.calls.last())
    }

    @Test fun notifyAndSpeakAreScopedAndMutedSpeechFails() = runTest {
        val (handler, token) = setup(ClientPermission.NOTIFY_SEND)
        val sent = handler.execute(token, "send_notification",
            args("title" to text("Water"), "message" to text("Drink"), "priority" to text("high"), "idempotency_key" to text("n1")))
        assertTrue(sent["success"]!!.jsonPrimitive.boolean)
        assertEquals(Triple("Water", "Drink", true), notifier.shown.single())
        assertEquals("permission_denied", handler.execute(token, "speak", args("message" to text("Hi"), "idempotency_key" to text("s")))["error_category"]!!.jsonPrimitive.content)
        val (muted, mutedToken) = setup(ClientPermission.NOTIFY_SPEAK, speaker = FakeSpeaker(muted = true))
        val failed = muted.execute(mutedToken, "speak", args("message" to text("Hi"), "idempotency_key" to text("s")))
        assertEquals("module_unavailable", failed["error_category"]!!.jsonPrimitive.content)
    }

    @Test fun nextAlarmNeedsReadGrant() = runTest {
        val (handler, token) = setup(ClientPermission.CLOCK_ALARM_READ)
        assertTrue(handler.execute(token, "get_next_alarm", JsonObject(emptyMap())).containsKey("next_alarm"))
        assertTrue("clock.alarm.read" in OAUTH_SCOPES && HealthActions.permissions.containsKey("speak"))
    }
}
