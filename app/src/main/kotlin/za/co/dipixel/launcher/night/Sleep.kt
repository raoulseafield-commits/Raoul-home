package za.co.dipixel.launcher.night

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.view.KeyEvent
import za.co.dipixel.launcher.MainActivity
import za.co.dipixel.launcher.data.ConfigStore

/**
 * Sleep timer. When it fires we pause whatever is playing (media key to the active
 * media session) and bring the launcher forward in its black "sleeping" state.
 * A non system app can't power the box off, so this is the closest honest equivalent.
 */
object Sleep {
    const val EXTRA_SLEEP = "sleep_now"
    private const val REQ = 4411

    private fun pending(context: Context): PendingIntent {
        val i = Intent(context, SleepReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, REQ, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(context: Context, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(context)
        am.cancel(pi)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        ConfigStore.save(context, ConfigStore.load(context).copy(sleepAt = at))
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(context))
        ConfigStore.save(context, ConfigStore.load(context).copy(sleepAt = 0L))
    }

    fun pauseMedia(context: Context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            audio.dispatchMediaKeyEvent(KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PAUSE))
        }
    }
}

class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Sleep.pauseMedia(context)
        ConfigStore.save(context, ConfigStore.load(context).copy(sleepAt = 0L))
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(Sleep.EXTRA_SLEEP, true)
        try { context.startActivity(launch) } catch (e: Exception) { /* background start blocked */ }
    }
}
