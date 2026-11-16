package io.github.sillypuppyy.dogboard.ime

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisAppActivity
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import io.github.sillypuppyy.dogboard.engine.TypingEngine
import kotlinx.coroutines.launch

/**
 * Tells the user each time adaptive autocorrect moves to another step (more careful, bolder, back
 * to their setting; user, 2026-11-16: "every time we change their autocorrection level (we should do
 * it rarely) and give them the option to disable it"). The step's hysteresis keeps it rare
 * (AutocorrectStrength.NOTIFY_AT). "Stop adjusting" turns adaptive autocorrect off; the switch
 * "Tell me when it adapts" (typing settings) turns the notifications off. Needs POST_NOTIFICATIONS
 * on Android 13+ (asked for by the typing settings); without it nothing is shown.
 */
object AdaptiveNotifier {
    private const val CHANNEL = "dogboard_adaptive"
    const val ID = 4207
    const val ACTION_STOP = "io.github.sillypuppyy.dogboard.ADAPTIVE_STOP"

    /** Hooks the engine's step changes to notifications (once per process). */
    fun install(context: Context) {
        val app = context.applicationContext
        // heard on the typing thread inside the strength's lock: posted from the background
        TypingEngine.onStrengthStepChange = { step -> kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { notify(app, step) } }
    }

    fun notify(context: Context, step: Int) {
        val prefs by FlorisPreferenceStore
        if (!prefs.correction.adaptiveNotify.get() || !prefs.correction.adaptiveStrength.get()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.adaptive_notify__channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val (title, text) = when (step) {
            1 -> R.string.adaptive_notify__careful__title to R.string.adaptive_notify__careful__text
            -1 -> R.string.adaptive_notify__bolder__title to R.string.adaptive_notify__bolder__text
            else -> R.string.adaptive_notify__back__title to R.string.adaptive_notify__back__text
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(Intent.ACTION_VIEW, Uri.parse("ui://florisboard/settings/typing"), context, FlorisAppActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, AdaptiveStopReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_app_icon_monochrome)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .apply { if (step != 0) addAction(0, context.getString(R.string.adaptive_notify__stop), stop) }
            .build()
        nm.notify(ID, n)
    }
}

/** "Stop adjusting": adaptive autocorrect off (the setting, and the engine at once). */
class AdaptiveStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AdaptiveNotifier.ACTION_STOP) return
        TypingEngine.adaptiveStrength = false
        context.getSystemService(NotificationManager::class.java)?.cancel(AdaptiveNotifier.ID)
        // preference writes suspend: finish the broadcast once it is stored
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val prefs by FlorisPreferenceStore
                prefs.correction.adaptiveStrength.set(false)
            } finally { pending.finish() }
        }
    }
}
