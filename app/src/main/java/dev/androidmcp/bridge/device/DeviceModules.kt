package dev.androidmcp.bridge.device

import dev.androidmcp.bridge.api.ActionModule
import dev.androidmcp.bridge.api.ModuleException
import dev.androidmcp.bridge.api.obj
import dev.androidmcp.bridge.api.text
import dev.androidmcp.bridge.security.ClientPermission
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId

/** How a Clock request reached the phone: opened directly, or parked in a notification the owner taps. */
enum class Delivery { SENT, NEEDS_TAP }

interface ClockDevice {
    fun nextAlarmMillis(): Long?
    fun setAlarm(hour: Int, minute: Int, label: String?, days: List<String>?): Delivery
    fun setTimer(seconds: Int, label: String?): Delivery
    fun dismissAlarm(mode: String, hour: Int?, minute: Int?, label: String?): Delivery
    fun showAlarms(): Delivery
}

interface PhoneNotifier { fun show(title: String, message: String, urgent: Boolean) }
interface PhoneSpeaker { suspend fun speak(message: String) }

private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.int(key: String) = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
private fun JsonObject.optionalLabel() = str("label")?.also { require(it.isNotBlank() && it.length <= 100) }
private fun JsonObject.only(vararg keys: String) = require(keys.toSet().containsAll(this.keys)) { "Unexpected field." }

private fun delivery(d: Delivery) = if (d == Delivery.SENT) "sent_to_clock" else "awaiting_tap_on_phone"
private fun deliveryNote(d: Delivery) =
    if (d == Delivery.SENT) "The request was handed to the Clock app." else "The phone was not in use. Tap the notification on the phone to finish."

class ClockModule(private val device: ClockDevice, private val settleMillis: Long = 1_500) : ActionModule {
    override val id = "clock"
    override val title = "Clock"
    override val operations get() = OPERATIONS
    override val idempotent = setOf("set_alarm", "set_timer")

    override suspend fun run(operation: String, args: JsonObject): JsonObject = when (operation) {
        "get_next_alarm" -> {
            require(args.isEmpty())
            val next = device.nextAlarmMillis()
            obj("next_alarm" to (next?.let { text(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()) } ?: JsonNull),
                "note" to text("Android only reveals the next alarm, not the full list."))
        }
        "show_alarms" -> {
            require(args.isEmpty())
            val d = device.showAlarms()
            obj("success" to JsonPrimitive(true), "status" to text(delivery(d)), "message" to text(deliveryNote(d)))
        }
        "set_alarm" -> {
            args.only("hour", "minute", "label", "days")
            val hour = requireNotNull(args.int("hour")); val minute = requireNotNull(args.int("minute"))
            require(hour in 0..23 && minute in 0..59)
            val days = (args["days"] as? JsonArray)?.map { requireNotNull((it as? JsonPrimitive)?.contentOrNull) }
            if (days != null) require(days.isNotEmpty() && days.toSet().size == days.size && days.all { it in WEEKDAYS })
            val label = args.optionalLabel()
            val d = device.setAlarm(hour, minute, label, days)
            if (d == Delivery.SENT) delay(settleMillis)
            obj("success" to JsonPrimitive(true), "status" to text(delivery(d)), "message" to text(deliveryNote(d)),
                "alarm" to text("%02d:%02d".format(hour, minute)), "repeat_days" to (days?.let { JsonArray(it.map(::text)) } ?: JsonNull),
                "next_alarm_on_phone" to (device.nextAlarmMillis()?.let {
                    text(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()) } ?: JsonNull))
        }
        "set_timer" -> {
            args.only("seconds", "label")
            val seconds = requireNotNull(args.int("seconds"))
            require(seconds in 1..86_400)
            val d = device.setTimer(seconds, args.optionalLabel())
            obj("success" to JsonPrimitive(true), "status" to text(delivery(d)), "message" to text(deliveryNote(d)), "seconds" to JsonPrimitive(seconds))
        }
        "dismiss_alarm" -> {
            args.only("mode", "hour", "minute", "label")
            val mode = requireNotNull(args.str("mode"))
            val hour = args.int("hour"); val minute = args.int("minute"); val label = args.optionalLabel()
            when (mode) {
                "next" -> require(hour == null && minute == null && label == null)
                "time" -> require(hour in 0..23 && minute in 0..59 && label == null)
                "label" -> require(label != null && hour == null && minute == null)
                else -> throw IllegalArgumentException("mode must be next, time or label.")
            }
            val d = device.dismissAlarm(mode, hour, minute, label)
            obj("success" to JsonPrimitive(true), "status" to text(delivery(d)), "message" to text(deliveryNote(d)))
        }
        else -> error("Unsupported operation.")
    }

    companion object {
        val WEEKDAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        val OPERATIONS: Map<String, ClientPermission?> = mapOf(
            "get_next_alarm" to ClientPermission.CLOCK_ALARM_READ, "show_alarms" to ClientPermission.CLOCK_ALARM_READ,
            "set_alarm" to ClientPermission.CLOCK_ALARM_SET, "dismiss_alarm" to ClientPermission.CLOCK_ALARM_DISMISS,
            "set_timer" to ClientPermission.CLOCK_TIMER_SET,
        )
    }
}

class NotifyModule(private val notifier: PhoneNotifier, private val speaker: PhoneSpeaker) : ActionModule {
    override val id = "notify"
    override val title = "Notify"
    override val operations get() = OPERATIONS
    override val idempotent = setOf("send_notification", "speak")

    override suspend fun run(operation: String, args: JsonObject): JsonObject = when (operation) {
        "send_notification" -> {
            args.only("title", "message", "priority")
            val title = requireNotNull(args.str("title")); val message = requireNotNull(args.str("message"))
            require(title.isNotBlank() && title.length <= 80 && message.isNotBlank() && message.length <= 500)
            val priority = args.str("priority") ?: "normal"
            require(priority == "normal" || priority == "high")
            notifier.show(title, message, priority == "high")
            obj("success" to JsonPrimitive(true), "status" to text("shown_on_phone"), "priority" to text(priority))
        }
        "speak" -> {
            args.only("message")
            val message = requireNotNull(args.str("message"))
            require(message.isNotBlank() && message.length <= 500)
            speaker.speak(message)
            obj("success" to JsonPrimitive(true), "status" to text("speaking"))
        }
        else -> error("Unsupported operation.")
    }

    companion object {
        val OPERATIONS: Map<String, ClientPermission?> = mapOf(
            "send_notification" to ClientPermission.NOTIFY_SEND, "speak" to ClientPermission.NOTIFY_SPEAK,
        )
    }
}

fun moduleUnavailable(message: String): Nothing = throw ModuleException("module_unavailable", message)
