package za.co.dipixel.launcher.watch

import android.content.Intent
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import za.co.dipixel.launcher.data.ConfigStore
import za.co.dipixel.launcher.hub.Autoplay

/**
 * The remote's Assistant button stays Google's (the box needs that), and Mi TV listens in:
 * it reads what you said off Google's own screen. If Google plays it, Mi TV does nothing.
 * If Google can't (a show on DStv, "many ways to watch", "open DStv"), Mi TV's agent takes the
 * same words and plays it, exactly as when you speak on Mi Remote.
 */
object AssistantWatch {
    private val googlePkgs = listOf("com.google.android.katniss", "com.google.android.apps.tv.assistant", "com.google.android.googlequicksearchbox", "com.google.android.apps.googleassistant")
    fun isGoogle(pkg: String) = googlePkgs.any { pkg == it }

    @Volatile private var active = false
    private val seen = LinkedHashSet<String>()
    private var service: ScreenReader? = null
    private var playingBefore = false

    private val command = Regex("^(?:hey google,? |ok google,? )?(play|watch|put on|open|start|launch|show me|find|listen to)\\b.{2,}", RegexOption.IGNORE_CASE)

    fun onGoogle(s: ScreenReader, e: AccessibilityEvent) {
        service = s
        if (!active) {
            if (ConfigStore.load(s).assistant != "handoff") return
            active = true
            synchronized(seen) { seen.clear() }
            playingBefore = anyPlaying(s)
            Thread { watch(s) }.start()
        }
        e.text?.forEach { add(it?.toString()) }
        collect(s)
    }

    private fun add(t: String?) {
        val x = t?.trim()?.trim('"', '“', '”')?.replace(Regex("\\s+"), " ") ?: return
        if (x.length in 3..120) synchronized(seen) { seen += x }
    }

    private fun collect(s: ScreenReader) {
        try {
            for (w in s.windows) {
                val root = w.root ?: continue
                if (!isGoogle(root.packageName?.toString() ?: "")) continue
                val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(root); var n = 0
                while (stack.isNotEmpty() && n < 400) {
                    val node = stack.removeLast(); n++
                    add(node.text?.toString()); add(node.contentDescription?.toString())
                    for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
                }
            }
        } catch (e: Exception) { }
    }

    private fun googleOnScreen(s: ScreenReader): Boolean = try {
        s.windows.any { w -> w.root?.packageName?.toString()?.let { isGoogle(it) } == true }
    } catch (e: Exception) { false }

    private fun anyPlaying(s: ScreenReader): Boolean = try {
        s.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(android.content.ComponentName(s, WatchListener::class.java))
            .any { it.playbackState?.state == PlaybackState.STATE_PLAYING }
    } catch (e: Exception) { false }

    private fun watch(s: ScreenReader) {
        try {
            val end = System.currentTimeMillis() + 30_000
            var goneSince = 0L
            while (System.currentTimeMillis() < end) {
                Thread.sleep(400)
                if (googleOnScreen(s)) { goneSince = 0; collect(s) }
                else { if (goneSince == 0L) goneSince = System.currentTimeMillis(); if (System.currentTimeMillis() - goneSince > 1200) break }
            }
            val said = synchronized(seen) { seen.toList() }.lastOrNull { command.matches(it) }
            Autoplay.noteExternal("Google heard: ${said ?: "(no command)"}  [${synchronized(seen) { seen.take(6).joinToString(" | ") }}]")
            if (said == null) return
            // give Google a few seconds to start it itself
            Thread.sleep(4500)
            val playing = anyPlaying(s)
            val front = ScreenReader.root()?.packageName?.toString() ?: ""
            if (playing && !playingBefore) { Autoplay.noteExternal("Google started it, Mi TV stays out"); return }
            // Google opened the app the person asked for and that was all they wanted ("open Netflix")
            if (Regex("^(open|start|launch)\\b", RegexOption.IGNORE_CASE).containsMatchIn(said) && front.isNotEmpty() && front != s.packageName && !isGoogle(front)) return
            Autoplay.noteExternal("Mi TV takes over: $said")
            val i = Intent(s, za.co.dipixel.launcher.voice.AssistActivity::class.java)
                .putExtra(za.co.dipixel.launcher.voice.AssistActivity.EXTRA_SAID, said)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { s.startActivity(i) } catch (e: Exception) { }
        } finally { active = false }
    }
}
