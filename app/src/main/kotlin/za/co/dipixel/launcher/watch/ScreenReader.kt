package za.co.dipixel.launcher.watch

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Live view of what is on screen. Used for two things only:
 *  - while Mi TV starts something you asked for (Search, profile picker and Play inside DStv / Netflix / Spotify)
 *  - noticing what you said to Google Assistant, so Mi TV can take over when Google can't play it
 */
class ScreenReader : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val p = event?.packageName?.toString() ?: return
        if (AssistantWatch.isGoogle(p)) AssistantWatch.onGoogle(this, event)
    }
    override fun onInterrupt() {}
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    companion object {
        @Volatile var instance: ScreenReader? = null
            private set

        /** Root of the app in front, or null when the helper isn't switched on. */
        fun root(): AccessibilityNodeInfo? = try { instance?.rootInActiveWindow } catch (e: Exception) { null }
    }
}
