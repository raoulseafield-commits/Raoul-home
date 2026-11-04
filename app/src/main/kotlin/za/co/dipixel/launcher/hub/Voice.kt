package za.co.dipixel.launcher.hub

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent
import za.co.dipixel.launcher.data.Apps
import za.co.dipixel.launcher.data.TvApp
import za.co.dipixel.launcher.night.Sleep
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Turns what someone said into an action on the TV. Returns a short message for the phone. */
object Voice {

    private val aliases = mapOf(
        "yt" to "youtube",
        "you tube" to "youtube",
        "youtube for kids" to "youtube kids",
        "kids youtube" to "youtube kids",
        "d stv" to "dstv",
        "dstv now" to "dstv",
        "prime" to "prime video",
        "amazon" to "prime video",
        "amazon prime" to "prime video",
        "disney" to "disney+",
        "disney plus" to "disney+",
        "google" to "google search"
    )

    fun handle(context: Context, raw: String): String {
        val t = raw.lowercase().trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return "Didn't catch that"

        // ---- simple controls ----
        when (t) {
            "pause", "pause it", "stop", "stop it", "hold on" -> return key(context, KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused")
            "play", "resume", "continue", "carry on", "unpause" -> return key(context, KeyEvent.KEYCODE_MEDIA_PLAY, "Playing")
            "volume up", "louder", "turn it up", "turn up the volume" -> return key(context, KeyEvent.KEYCODE_VOLUME_UP, "Louder", 3)
            "volume down", "softer", "quieter", "turn it down", "turn down the volume" -> return key(context, KeyEvent.KEYCODE_VOLUME_DOWN, "Softer", 3)
            "mute", "unmute", "mute the tv" -> return key(context, KeyEvent.KEYCODE_VOLUME_MUTE, "Mute toggled")
            "home", "go home", "go to home", "home screen" -> return key(context, KeyEvent.KEYCODE_HOME, "Home")
            "back", "go back" -> return key(context, KeyEvent.KEYCODE_BACK, "Back")
            "next", "skip", "next episode", "next song" -> return key(context, KeyEvent.KEYCODE_MEDIA_NEXT, "Next")
            "previous", "previous song", "go back a song" -> return key(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Previous")
        }
        Regex("^(?:sleep|turn off|switch off)(?: the tv)? in (\\d{1,3}) ?(?:min|mins|minutes)$").find(t)?.let {
            val m = it.groupValues[1].toInt().coerceIn(1, 240)
            Sleep.schedule(context, m)
            return "Sleeping in $m minutes"
        }

        val apps = Apps.installed(context)

        // ---- open an app ----
        Regex("^(?:open|launch|start|go to|switch to|show) (.+)$").find(t)?.let {
            val app = findApp(apps, it.groupValues[1])
            if (app != null) {
                return if (start(context, launchIntent(context, app.packageName))) "Opening ${app.label}" else "Couldn't open ${app.label}"
            }
        }

        // ---- play something ----
        var rest = t
        Regex("^(?:play|watch|put on|listen to|show me|find|search for|search) (.+)$").find(t)?.let { rest = it.groupValues[1] }
        var app: TvApp? = null
        for (sep in listOf(" on ", " in ", " from ", " using ")) {
            val i = rest.lastIndexOf(sep)
            if (i > 0) {
                val maybe = findApp(apps, rest.substring(i + sep.length))
                if (maybe != null) { app = maybe; rest = rest.substring(0, i).trim(); break }
            }
        }
        // "netflix stranger things" / "youtube baby shark"
        if (app == null) {
            for (a in apps.sortedByDescending { it.label.length }) {
                val n = a.label.lowercase()
                if (rest.startsWith("$n ") && rest.length > n.length + 1) { app = a; rest = rest.substring(n.length + 1).trim(); break }
            }
        }
        // only an app name was said
        if (app == null) findApp(apps, rest)?.let { a ->
            return if (start(context, launchIntent(context, a.packageName))) "Opening ${a.label}" else "Couldn't open ${a.label}"
        }
        val query = rest.removePrefix("the song ").removePrefix("the movie ").removePrefix("the show ").trim()
        if (query.isEmpty()) return "What should I play?"

        if (app == null) {
            val music = Regex("\\b(song|music|album|playlist|track|by)\\b").containsMatchIn(t)
            app = when {
                music -> findApp(apps, "spotify")
                Regex("\\b(video|youtube)\\b").containsMatchIn(t) -> findApp(apps, "youtube")
                else -> null
            }
        }
        if (app == null) {
            // Google TV universal search: shows the title and which app has it
            if (start(context, Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH).putExtra(SearchManager.QUERY, query))) return "Searching Google TV for \"$query\""
            app = findApp(apps, "youtube") ?: return "No app found to play \"$query\""
        }
        return play(context, app, query)
    }

    fun play(context: Context, app: TvApp, query: String): String {
        val pkg = app.packageName
        if (pkg.contains("youtube")) za.co.dipixel.launcher.watch.Watching.record(context, query.replaceFirstChar { it.uppercase() }, pkg)
        val q = URLEncoder.encode(query, "UTF-8")

        if (pkg.contains("youtube")) {
            val id = youtubeFirstVideo(query)
            if (id != null && start(context, view("https://www.youtube.com/watch?v=$id", pkg))) return "Playing \"$query\" on ${app.label}"
            if (start(context, view("https://www.youtube.com/results?search_query=$q", pkg))) return "Searching ${app.label} for \"$query\""
            return if (start(context, launchIntent(context, pkg))) "Opened ${app.label}" else "Couldn't open ${app.label}"
        }

        if (pkg.contains("spotify")) return Autoplay.spotify(context, app, query)
        // films and series: find the exact title and play it (Netflix, DStv, ...)
        Agent.playByName(context, query, pkg)?.let { return it }

        // Most media apps support Android's "play from search" (it's what Google Assistant uses)
        val fromSearch = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(pkg)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        if (start(context, fromSearch)) return "Playing \"$query\" on ${app.label}"

        val searchLinks = when {
            pkg.contains("spotify") -> listOf("spotify:search:$q")
            pkg.contains("netflix") -> listOf("nflx://www.netflix.com/search?q=$q", "https://www.netflix.com/search?q=$q")
            else -> emptyList()
        }
        for (link in searchLinks) if (start(context, view(link, pkg))) return "Searching ${app.label} for \"$query\""

        val search = Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, query)
        if (start(context, search)) return "Searching ${app.label} for \"$query\""

        return if (start(context, launchIntent(context, pkg))) "Opened ${app.label}, search there for \"$query\"" else "Couldn't open ${app.label}"
    }

    // ---------------- helpers ----------------

    fun findApp(apps: List<TvApp>, spoken: String): TvApp? {
        var s = spoken.lowercase().trim().removePrefix("the ").removeSuffix(" app").trim()
        s = aliases[s] ?: s
        val n = norm(s)
        if (n.isEmpty()) return null
        apps.firstOrNull { norm(it.label) == n }?.let { return it }
        if (n == "googlesearch") apps.firstOrNull { norm(it.label) in setOf("google", "search", "googleassistant") }?.let { return it }
        apps.filter { norm(it.label).startsWith(n) }.minByOrNull { it.label.length }?.let { return it }
        if (n.length >= 4) apps.filter { norm(it.label).contains(n) }.minByOrNull { it.label.length }?.let { return it }
        return null
    }

    private fun norm(s: String) = s.lowercase().replace("+", "plus").replace(Regex("[^a-z0-9]"), "")

    private fun view(url: String, pkg: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)

    private fun launchIntent(context: Context, pkg: String): Intent? =
        context.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: context.packageManager.getLaunchIntentForPackage(pkg)

    /** Starts an activity if something on the box can handle it. */
    fun start(context: Context, intent: Intent?): Boolean {
        if (intent == null) return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val handlers = context.packageManager.queryIntentActivities(intent, 0)
        if (handlers.isEmpty()) return false
        return try {
            context.startActivity(intent); true
        } catch (e: Exception) {
            // background start blocked: ask adb to do it
            val target = handlers.first().activityInfo
            val cmd = buildString {
                append("am start")
                intent.action?.let { append(" -a ").append(Shell.quote(it)) }
                intent.dataString?.let { append(" -d ").append(Shell.quote(it)) }
                intent.getStringExtra(SearchManager.QUERY)?.let { append(" --es query ").append(Shell.quote(it)) }
                append(" -n ").append(Shell.quote(target.packageName + "/" + target.name))
            }
            val out = Shell.run(context, cmd) ?: return false
            !out.contains("Error")
        }
    }

    private fun key(context: Context, code: Int, msg: String, times: Int = 1): String =
        if (Hub.pressKey(context, code, times)) msg else "Couldn't reach the TV controls"

    /** First real (non ad) result from a YouTube search page, no API key needed. */
    fun youtubeFirstVideo(query: String): String? = try {
        val url = URL("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8"))
        val c = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 6000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            setRequestProperty("Accept-Language", "en-ZA,en;q=0.9")
        }
        val html = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        Regex("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(html)?.groupValues?.get(1)
            ?: Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(html)?.groupValues?.get(1)
    } catch (e: Exception) { null }
}
