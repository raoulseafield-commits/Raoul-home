package za.co.dipixel.launcher.hub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import za.co.dipixel.launcher.data.ConfigStore

/**
 * Box-wide tweaks done through the box's own adb (all reversible):
 *  - speed: shorter animations, Mi TV compiled ahead of time, apps optimised in the background,
 *    Google's hidden background extras switched off (nothing you can see or open).
 *  - TV power over HDMI (CEC): box off turns the TV off, box on turns the TV on and switches to the box.
 */
object SystemTune {
    private const val VERSION = 1
    @Volatile var lastResult = "not run yet"
        private set

    /** Hidden background packages only. Apps you can open are never touched, so hidden apps can still be re-added. */
    private val BACKGROUND_EXTRAS = listOf(
        "com.google.android.apps.tv.dreamx",            // Google's screensaver (Mi TV Art replaces it)
        "com.google.android.tvrecommendations",         // feeds the stock home screen, which is off
        "com.google.android.leanbacklauncher.recommendations",
        "com.google.android.feedback"
    )

    fun applyIfNeeded(context: Context) {
        val p = context.getSharedPreferences("tune", Context.MODE_PRIVATE)
        if (p.getInt("v", 0) >= VERSION) { cec(context); return }
        if (apply(context)) p.edit().putInt("v", VERSION).apply()
    }

    fun apply(context: Context): Boolean {
        if (Shell.run(context, "echo ok")?.trim() != "ok") { lastResult = "waiting for adb approval on the TV"; return false }
        val pkg = context.packageName
        val speed = buildString {
            append("settings put global window_animation_scale 0.5; ")
            append("settings put global transition_animation_scale 0.5; ")
            append("settings put global animator_duration_scale 0.5; ")
            for (b in BACKGROUND_EXTRAS) append("pm list packages | grep -q '^package:$b$' && pm disable-user --user 0 $b >/dev/null 2>&1; ")
            append("pm trim-caches 999G >/dev/null 2>&1; ")
        }
        Shell.run(context, speed)
        cec(context)
        // AOT compile Mi TV and let Android optimise every app, in the background (takes a few minutes, once)
        Shell.runDetached(context, "(nohup sh -c 'cmd package compile -m speed -f $pkg; cmd package bg-dexopt-job' >/dev/null 2>&1 &) ; exit 0")
        lastResult = "applied: animations 0.5x, apps optimising, background extras off, TV power linked"
        return true
    }

    /**
     * Who answers the remote's Assistant button. Mi TV: the box's assistant slot points at Mi TV,
     * which still uses Google's own listening. Google: back to the stock Google Assistant.
     */
    fun applyAssistant(context: Context) {
        val pkg = context.packageName
        val mine = ConfigStore.load(context).assistant == "mitv"
        val google = listOf("com.google.android.katniss", "com.google.android.apps.tv.assistant")
            .firstOrNull { Shell.run(context, "pm list packages $it")?.contains("package:$it") == true } ?: "com.google.android.katniss"
        val out = if (mine) Shell.run(context,
            "cmd role add-role-holder --user 0 android.app.role.ASSISTANT $pkg 0 2>&1; settings put secure assistant $pkg/$pkg.voice.AssistActivity; cmd role get-role-holders --user 0 android.app.role.ASSISTANT")
        else Shell.run(context,
            "cmd role remove-role-holder --user 0 android.app.role.ASSISTANT $pkg 0 2>&1; cmd role add-role-holder --user 0 android.app.role.ASSISTANT $google 0 2>&1; cmd role get-role-holders --user 0 android.app.role.ASSISTANT")
        assistantResult = (out ?: "adb not ready").trim().lines().lastOrNull()?.take(80) ?: ""
        context.getSharedPreferences("tune", Context.MODE_PRIVATE).edit().putString("assistant", if (mine) "mitv" else "google").apply()
    }
    @Volatile var assistantResult = ""

    fun applyAssistantIfChanged(context: Context) {
        val want = ConfigStore.load(context).assistant
        if (context.getSharedPreferences("tune", Context.MODE_PRIVATE).getString("assistant", "") != want) applyAssistant(context)
    }

    /** Switch on the Mi TV play helper (accessibility) without touching any others that are on. */
    fun enablePlayHelper(context: Context) {
        val me = "${context.packageName}/${context.packageName}.watch.ScreenReader"
        val cur = Shell.run(context, "settings get secure enabled_accessibility_services")?.trim() ?: return
        if (cur.contains(me)) return
        val list = if (cur.isBlank() || cur == "null") me else "$cur:$me"
        Shell.run(context, "settings put secure enabled_accessibility_services ${Shell.quote(list)}; settings put secure accessibility_enabled 1")
    }

    /** HDMI-CEC: keep the box and the Samsung (Anynet+) in step. */
    fun cec(context: Context) {
        val follow = ConfigStore.load(context).tvFollowsBox
        Shell.run(context, buildString {
            append("settings put global hdmi_control_enabled 1; ")
            append("settings put global hdmi_control_auto_device_off_enabled 1; ")
            append("settings put global hdmi_control_auto_wakeup_enabled ${if (follow) 1 else 0}; ")
            append("cmd hdmi_control cec_setting set hdmi_cec_enabled 1 >/dev/null 2>&1; ")
            append("cmd hdmi_control cec_setting set power_control_mode to_tv >/dev/null 2>&1; ")
            // stops the box going back to sleep when the TV is switched on with its own remote
            append("cmd hdmi_control cec_setting set power_state_change_on_active_source_lost none >/dev/null 2>&1; ")
        })
    }

    /** Box just woke up: turn the TV on and switch it to the box (One Touch Play). */
    fun wakeTv(context: Context) {
        if (!ConfigStore.load(context).tvFollowsBox) return
        Thread {
            for (attempt in 0 until 2) {
                val out = Shell.run(context, "cmd hdmi_control onetouchplay 2>&1") ?: ""
                lastResult = "TV wake: ${out.trim().take(80).ifBlank { "sent" }}"
                Thread.sleep(5000)   // some TVs miss the first one while the HDMI link comes up
            }
        }.start()
    }

    /** Listens for the box waking up (remote power button, TV remote via Anynet+, timer). */
    fun registerWake(context: Context) {
        val h = Handler(Looper.getMainLooper())
        try {
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { h.postDelayed({ wakeTv(c.applicationContext) }, 700) }
            }, IntentFilter(Intent.ACTION_SCREEN_ON))
        } catch (e: Exception) { }
    }
}
