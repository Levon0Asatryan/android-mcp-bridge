package dev.androidmcp.bridge.device

import android.app.AlarmManager
import android.app.Application
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.provider.AlarmClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.androidmcp.bridge.MainActivity
import dev.androidmcp.bridge.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger

/**
 * Clock requests use the standard alarm intents, which Samsung Clock honours. Android blocks activities started from
 * the background, so unless the app is on screen the request waits in a notification that the owner taps.
 */
class AndroidClock(private val app: Application, private val appVisible: () -> Boolean) : ClockDevice {
    private val notes = AtomicInteger(NOTE_BASE)
    init { app.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL, "Clock requests", NotificationManager.IMPORTANCE_HIGH)) }

    override fun nextAlarmMillis(): Long? = app.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime

    override fun setAlarm(hour: Int, minute: Int, label: String?, days: List<String>?): Delivery {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        days?.let { list -> intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(list.map { DAYS.getValue(it) })) }
        return deliver(intent, "Set alarm %02d:%02d".format(hour, minute) + (label?.let { " ($it)" } ?: ""))
    }

    override fun setTimer(seconds: Int, label: String?): Delivery {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return deliver(intent, "Start timer for ${seconds / 60} min ${seconds % 60} s")
    }

    override fun dismissAlarm(mode: String, hour: Int?, minute: Int?, label: String?): Delivery {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM)
        when (mode) {
            "next" -> intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
            "time" -> intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            else -> intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
        }
        return deliver(intent, "Dismiss alarm")
    }

    override fun showAlarms() = deliver(Intent(AlarmClock.ACTION_SHOW_ALARMS), "Open alarms")

    private fun deliver(intent: Intent, prompt: String): Delivery {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (appVisible() && intent.resolveActivity(app.packageManager) != null) {
            runCatching { app.startActivity(intent) }.onSuccess { return Delivery.SENT }
        }
        val manager = app.getSystemService(NotificationManager::class.java)
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) moduleUnavailable("Notifications are disabled for Android MCP Bridge, so the request cannot reach the owner.")
        val tap = PendingIntent.getActivity(app, notes.get(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(notes.getAndUpdate { if (it >= NOTE_BASE + 49) NOTE_BASE else it + 1 },
            NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_bridge).setContentTitle("Tap to confirm")
                .setContentText(prompt).setContentIntent(tap).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER).build())
        return Delivery.NEEDS_TAP
    }

    private companion object {
        const val CHANNEL = "clock_requests"
        const val NOTE_BASE = 5_000
        val DAYS = mapOf("mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY,
            "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY, "sun" to Calendar.SUNDAY)
    }
}

class AndroidNotifier(private val app: Application) : PhoneNotifier {
    private val ids = AtomicInteger(NOTE_BASE)
    init {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(NORMAL, "Messages from AI", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(URGENT, "Urgent messages from AI", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun show(title: String, message: String, urgent: Boolean) {
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) moduleUnavailable("Notifications are disabled for Android MCP Bridge. Turn them on in phone settings.")
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val hidden = NotificationCompat.Builder(app, if (urgent) URGENT else NORMAL).setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle("Message from AI").build()
        val id = ids.getAndUpdate { if (it >= NOTE_BASE + 49) NOTE_BASE else it + 1 }
        app.getSystemService(NotificationManager::class.java).notify(id,
            NotificationCompat.Builder(app, if (urgent) URGENT else NORMAL).setSmallIcon(R.drawable.ic_bridge)
                .setContentTitle(title).setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(hidden)
                .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT).build())
    }

    private companion object { const val NORMAL = "ai_messages"; const val URGENT = "ai_urgent"; const val NOTE_BASE = 6_000 }
}

class AndroidSpeaker(private val app: Application) : PhoneSpeaker {
    private var engine: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null

    private suspend fun engine(): TextToSpeech {
        synchronized(this) {
            if (ready == null) {
                val signal = CompletableDeferred<Boolean>()
                ready = signal
                engine = TextToSpeech(app) { status -> signal.complete(status == TextToSpeech.SUCCESS) }
            }
        }
        val ok = withTimeoutOrNull(10_000) { ready!!.await() } == true
        if (!ok) { synchronized(this) { engine?.shutdown(); engine = null; ready = null }; moduleUnavailable("Text to speech is not available on the phone.") }
        return engine!!
    }

    override suspend fun speak(message: String) {
        val audio = app.getSystemService(AudioManager::class.java)
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) moduleUnavailable("The phone's media volume is muted.")
        val tts = engine()
        tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        val started = CompletableDeferred<Boolean>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { started.complete(true) }
            override fun onDone(utteranceId: String?) { started.complete(true) }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { started.complete(false) }
        })
        val id = "amb-${System.nanoTime()}"
        if (tts.speak(message, TextToSpeech.QUEUE_FLUSH, Bundle(), id) != TextToSpeech.SUCCESS) moduleUnavailable("The phone could not start speaking.")
        if (withTimeoutOrNull(8_000) { started.await() } == false) moduleUnavailable("The phone could not speak this message.")
    }
}

/** Counts started activities so the Clock requests know whether the app is on screen. */
class VisibilityTracker : Application.ActivityLifecycleCallbacks {
    private val started = AtomicInteger()
    val visible get() = started.get() > 0
    override fun onActivityStarted(activity: Activity) { started.incrementAndGet() }
    override fun onActivityStopped(activity: Activity) { started.decrementAndGet() }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
