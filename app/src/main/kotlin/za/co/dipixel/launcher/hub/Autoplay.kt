package za.co.dipixel.launcher.hub

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import za.co.dipixel.launcher.data.ConfigStore
import za.co.dipixel.launcher.data.TvApp
import za.co.dipixel.launcher.watch.WatchListener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Gets from "the app is open" to "it is actually playing".
 *  - Netflix / DStv: open the title, get past "Who's watching?" (picks this person's profile, first by default),
 *    open the title again if the profile screen swallowed it, press Play / Resume when the page shows.
 *  - Spotify: finds the exact track link (no key needed), opens it and tells Spotify to play.
 * Everything it tried is kept in [trace] so /diag can show why something didn't start.
 */
object Autoplay {
    @Volatile var trace: List<String> = emptyList()
        private set
    private val log = ArrayList<String>()
    fun noteExternal(s: String) = note(s)
    private fun note(s: String) { synchronized(log) { log += "${System.currentTimeMillis() / 1000 % 100000} $s"; while (log.size > 40) log.removeAt(0); trace = log.toList() } }

    // ---------------- media sessions ----------------

    private fun controllers(context: Context): List<MediaController> = try {
        context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(context, WatchListener::class.java))
    } catch (e: Exception) { emptyList() }

    fun controller(context: Context, pkg: String): MediaController? = controllers(context).firstOrNull { it.packageName == pkg }

    fun isPlaying(context: Context, pkg: String): Boolean =
        controller(context, pkg)?.playbackState?.state.let { it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING }

    // ---------------- screen reading ----------------

    data class Node(val text: String, val desc: String, val id: String, val pkg: String, val bounds: Rect, val clickable: Boolean, val focused: Boolean,
                    val editable: Boolean = false, val ref: android.view.accessibility.AccessibilityNodeInfo? = null) {
        val label get() = (text.ifBlank { desc }).trim()
    }

    /** Live nodes from the play helper (accessibility); null when it isn't on. */
    private fun liveScreen(): List<Node>? {
        val root = za.co.dipixel.launcher.watch.ScreenReader.root() ?: return null
        val out = ArrayList<Node>()
        val stack = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty() && out.size < 1500) {
            val n = stack.removeLast()
            val r = Rect(); n.getBoundsInScreen(r)
            out += Node(n.text?.toString() ?: "", n.contentDescription?.toString() ?: "", n.viewIdResourceName ?: "", n.packageName?.toString() ?: "",
                r, n.isClickable, n.isFocused, n.isEditable, n)
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.add(it) }
        }
        return out
    }

    fun helperOn() = za.co.dipixel.launcher.watch.ScreenReader.instance != null

    private val attr = Regex("(\\w[\\w-]*)=\"([^\"]*)\"")

    fun screen(context: Context): List<Node> {
        liveScreen()?.let { if (it.isNotEmpty()) return it }
        val xml = Shell.run(context, "uiautomator dump --compressed /data/local/tmp/mitv_ui.xml >/dev/null 2>&1; cat /data/local/tmp/mitv_ui.xml") ?: return emptyList()
        val out = ArrayList<Node>()
        for (m in Regex("<node ([^>]*)/?>").findAll(xml)) {
            val a = attr.findAll(m.groupValues[1]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
            val b = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").find(a["bounds"] ?: "") ?: continue
            val (l, t, r, bt) = b.destructured
            out += Node(a["text"] ?: "", a["content-desc"] ?: "", a["resource-id"] ?: "", a["package"] ?: "",
                Rect(l.toInt(), t.toInt(), r.toInt(), bt.toInt()), a["clickable"] == "true", a["focused"] == "true")
        }
        return out
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")

    private fun tap(context: Context, n: Node) {
        // click the node itself, or the nearest clickable parent (cards often wrap their text)
        var r = n.ref
        var depth = 0
        while (r != null && depth < 6) {
            if (r.isClickable && r.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return
            r = r.parent; depth++
        }
        if (n.focused) Hub.pressKey(context, KeyEvent.KEYCODE_DPAD_CENTER)
        else Shell.run(context, "input tap ${n.bounds.centerX()} ${n.bounds.centerY()}")
    }

    private fun typeInto(context: Context, field: Node?, text: String) {
        val ref = field?.ref
        if (ref != null) {
            ref.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            if (ref.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return
        }
        Hub.typeText(context, text)
    }

    private val profileGate = Regex("who'?s watching|who is watching|choose (a |your )?profile|select (a |your )?profile|switch profile|manage profiles", RegexOption.IGNORE_CASE)
    private val playWords = Regex("^(play|watch|resume|continue|watch now|play now|resume playing|continue watching|start watching|play episode|play from beginning|watch from beginning|play movie|play s\\d+)\\b", RegexOption.IGNORE_CASE)
    private val notPlay = Regex("trailer|preview|playlist|my list|download", RegexOption.IGNORE_CASE)

    /** The person's profile position inside Netflix / DStv (0 = first). */
    private fun profileSlot(context: Context): Int {
        val c = ConfigStore.load(context)
        return c.profiles.getOrNull(c.activeProfile)?.appProfile ?: 0
    }

    private fun pickProfile(context: Context) {
        val slot = profileSlot(context)
        note("profile screen: choosing profile ${slot + 1}")
        Hub.pressKey(context, KeyEvent.KEYCODE_DPAD_LEFT, 6)      // back to the first profile
        Thread.sleep(250)
        if (slot > 0) { Hub.pressKey(context, KeyEvent.KEYCODE_DPAD_RIGHT, slot); Thread.sleep(250) }
        Hub.pressKey(context, KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /**
     * Watches [pkg] for up to [maxMs] and nudges it until playback starts.
     * [reopen] re-sends the title link (used after a profile screen).
     */
    fun follow(context: Context, pkg: String, title: String, reopen: (() -> Boolean)?, maxMs: Long = 40_000) {
        Thread {
            val end = System.currentTimeMillis() + maxMs
            var profileDone = false
            var presses = 0
            var blindTried = false
            Thread.sleep(3500)
            while (System.currentTimeMillis() < end) {
                if (isPlaying(context, pkg)) { note("$title: playing in $pkg"); return@Thread }
                val nodes = screen(context)
                val fg = nodes.firstOrNull()?.pkg ?: ""
                if (fg.isNotEmpty() && fg != pkg) { note("$title: left $pkg (now $fg), stopping"); return@Thread }
                val all = nodes.joinToString(" ") { it.label }
                when {
                    !profileDone && profileGate.containsMatchIn(all) -> {
                        profileDone = true; pickProfile(context); Thread.sleep(4500)
                        if (reopen != null) { note("$title: opening the title again after the profile"); reopen() }
                        Thread.sleep(3500); continue
                    }
                    else -> {
                        val btn = nodes.filter { playWords.containsMatchIn(it.label) && !notPlay.containsMatchIn(it.label) && it.bounds.width() > 0 }
                            .minByOrNull { if (it.focused) 0 else 1 }
                        if (btn != null && presses < 3) {
                            presses++; note("$title: pressing \"${btn.label.take(30)}\""); tap(context, btn); Thread.sleep(4000); continue
                        }
                        // the app shows nothing readable (Netflix often): try the profile screen blind, once
                        if (nodes.size < 4 && !profileDone && !blindTried && reopen != null) {
                            blindTried = true; profileDone = true; pickProfile(context); Thread.sleep(4500)
                            note("$title: re-opening after a blind profile pick"); reopen(); Thread.sleep(3500); continue
                        }
                        // a session that is ready but paused just needs a play
                        controller(context, pkg)?.let { c ->
                            val st = c.playbackState?.state
                            if (st == PlaybackState.STATE_PAUSED || st == PlaybackState.STATE_STOPPED) { note("$title: session paused, sending play"); c.transportControls.play() }
                        }
                    }
                }
                Thread.sleep(2500)
            }
            note("$title: gave up waiting for playback in $pkg")
        }.start()
    }

    // ---------------- Spotify ----------------

    /** First Spotify link for a search, from the open web (no Spotify key needed). */
    fun spotifyUri(query: String): String? {
        val q = URLEncoder.encode("site:open.spotify.com $query", "UTF-8")
        val pages = listOf("https://html.duckduckgo.com/html/?q=$q", "https://www.bing.com/search?q=$q&setlang=en")
        val re = Regex("open\\.spotify\\.com(?:%2F|/)(?:intl-[a-z]{2}(?:%2F|/))?(track|album|playlist|artist)(?:%2F|/)([A-Za-z0-9]{22})")
        for (p in pages) try {
            val c = (URL(p).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000; readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                setRequestProperty("Accept-Language", "en-ZA,en;q=0.9")
            }
            val html = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val hits = re.findAll(html).map { it.groupValues[1] to it.groupValues[2] }.distinct().toList()
            // a song name usually means a track; "by <artist>" alone or "playlist" picks those first
            val want = when {
                Regex("\\bplaylist\\b", RegexOption.IGNORE_CASE).containsMatchIn(query) -> "playlist"
                Regex("\\balbum\\b", RegexOption.IGNORE_CASE).containsMatchIn(query) -> "album"
                else -> "track"
            }
            val pick = hits.firstOrNull { it.first == want } ?: hits.firstOrNull()
            if (pick != null) return "spotify:${pick.first}:${pick.second}"
        } catch (e: Exception) { }
        return null
    }

    /** Play a song / artist / playlist on Spotify. Returns a short message. Runs the slow parts in the background. */
    fun spotify(context: Context, app: TvApp, query: String): String {
        val pkg = app.packageName
        Thread {
            val uri = spotifyUri(query)
            note("spotify \"$query\" -> ${uri ?: "no link found"}")
            val opened = uri != null && Voice.start(context, Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg))
            if (!opened) Voice.start(context, context.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: context.packageManager.getLaunchIntentForPackage(pkg))
            // Spotify's own player accepts "play this" directly once it is up
            val end = System.currentTimeMillis() + 15_000
            var asked = false
            while (System.currentTimeMillis() < end) {
                Thread.sleep(1500)
                val c = controller(context, pkg) ?: continue
                if (!asked) {
                    asked = true
                    try {
                        if (uri != null) { note("spotify: playFromUri"); c.transportControls.playFromUri(Uri.parse(uri), Bundle()) }
                        else { note("spotify: playFromSearch"); c.transportControls.playFromSearch(query, Bundle()) }
                    } catch (e: Exception) { }
                    Thread.sleep(2500)
                }
                if (isPlaying(context, pkg)) {
                    // make sure it is the song we asked for, not what was playing last time
                    note("spotify: playing ${c.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: ""}")
                    return@Thread
                }
                c.transportControls.play()
            }
            // still nothing: drive the screen (track page Play button, or first search result)
            note("spotify: no session answer, using the screen")
            follow(context, pkg, query, if (uri != null) ({ Voice.start(context, Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg)) }) else null, 25_000)
        }.start()
        return "Playing \"$query\" on Spotify"
    }

    // ---------------- search inside an app (DStv when it has no link) ----------------

    private val searchWord = Regex("(^|[^a-z])search([^a-z]|$)", RegexOption.IGNORE_CASE)

    /** Opens the app's own search, types the title, opens the best match and presses play. */
    fun searchAndPlay(context: Context, pkg: String, title: String) {
        Thread {
            Thread.sleep(4500)
            fun now() = screen(context)
            var nodes = now()
            if (profileGate.containsMatchIn(nodes.joinToString(" ") { it.label })) { pickProfile(context); Thread.sleep(5000); nodes = now() }
            fun findSearch(ns: List<Node>) = ns.firstOrNull { searchWord.containsMatchIn(it.label) && it.label.length < 30 }
                ?: ns.firstOrNull { it.id.contains("search", true) }
            var search = findSearch(nodes)
            if (search == null) {
                // side menus on TV apps open with Left; Search is usually the top item
                note("$title: opening the side menu to find Search")
                Hub.pressKey(context, KeyEvent.KEYCODE_DPAD_LEFT, 2); Thread.sleep(1500)
                nodes = now(); search = findSearch(nodes)
                if (search == null) { Hub.pressKey(context, KeyEvent.KEYCODE_DPAD_UP, 6); Thread.sleep(1200); nodes = now(); search = findSearch(nodes) }
            }
            if (search == null) { note("$title: no Search found in $pkg (helper ${if (helperOn()) "on" else "off"}, ${nodes.size} items on screen)"); return@Thread }
            note("$title: opening Search"); tap(context, search); Thread.sleep(2500)
            val field = now().firstOrNull { it.editable }
            note("$title: typing (${if (field != null) "text box" else "keys"})")
            typeInto(context, field, title); Thread.sleep(4000)
            val words = title.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(" ").filter { it.length > 2 && it != "the" }
            var hit: Node? = null
            for (attempt in 0 until 3) {
                hit = now().filter { n ->
                    val l = n.label.lowercase()
                    !n.editable && l.isNotBlank() && l.length < 80 && words.all { l.contains(it) } && n.bounds.width() > 0
                }.maxByOrNull { (if (it.clickable) 2 else 0) + (if (it.label.lowercase().startsWith(title.lowercase())) 1 else 0) }
                if (hit != null) break
                Thread.sleep(2000)
            }
            if (hit == null) { note("$title: no search result matched"); return@Thread }
            note("$title: opening result \"${hit.label.take(30)}\""); tap(context, hit)
            follow(context, pkg, title, null, 30_000)
        }.start()
    }
}
