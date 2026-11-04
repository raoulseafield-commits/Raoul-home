package za.co.dipixel.launcher.watch

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Live view of what is on screen, used only while Mi TV is starting something you asked for
 * (finding Search, the profile picker and the Play button inside DStv / Netflix / Spotify).
 * Much faster than a screen dump, and it works while trailers are playing.
 */
class ScreenReader : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    companion object {
        @Volatile var instance: ScreenReader? = null
            private set

        /** Root of the app in front, or null when the helper isn't switched on. */
        fun root(): AccessibilityNodeInfo? = try { instance?.rootInActiveWindow } catch (e: Exception) { null }
    }
}
