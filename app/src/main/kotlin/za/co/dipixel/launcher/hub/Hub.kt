package za.co.dipixel.launcher.hub

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.view.KeyEvent
import androidx.core.graphics.drawable.toBitmap
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import za.co.dipixel.launcher.MainActivity
import za.co.dipixel.launcher.art.ArtRepo
import za.co.dipixel.launcher.data.Apps
import za.co.dipixel.launcher.data.Artwork
import za.co.dipixel.launcher.data.ConfigStore
import za.co.dipixel.launcher.data.Show
import za.co.dipixel.launcher.data.TvApp
import za.co.dipixel.launcher.night.Sleep
import za.co.dipixel.launcher.ui.favouriteApps
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

object Hub {
    const val PORT = 8765
    const val CONTROL_PORT = 8767
    const val TV_NAME = "Mi TV"

    private val keyNames = mapOf(
        "up" to KeyEvent.KEYCODE_DPAD_UP,
        "down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "left" to KeyEvent.KEYCODE_DPAD_LEFT,
        "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "ok" to KeyEvent.KEYCODE_DPAD_CENTER,
        "back" to KeyEvent.KEYCODE_BACK,
        "home" to KeyEvent.KEYCODE_HOME,
        "menu" to KeyEvent.KEYCODE_MENU,
        "playpause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
        "prev" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        "rewind" to KeyEvent.KEYCODE_MEDIA_REWIND,
        "forward" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        "volup" to KeyEvent.KEYCODE_VOLUME_UP,
        "voldown" to KeyEvent.KEYCODE_VOLUME_DOWN,
        "mute" to KeyEvent.KEYCODE_VOLUME_MUTE,
        "settings" to KeyEvent.KEYCODE_SETTINGS,
        "delete" to KeyEvent.KEYCODE_DEL,
        "enter" to KeyEvent.KEYCODE_ENTER,
        "power" to KeyEvent.KEYCODE_POWER,
        "search" to KeyEvent.KEYCODE_SEARCH,
        "input" to KeyEvent.KEYCODE_TV_INPUT,
        "apps" to KeyEvent.KEYCODE_ALL_APPS
    )

    fun keyCode(name: String): Int? = keyNames[name.lowercase()] ?: name.toIntOrNull()

    /** Press a key on the TV. Volume and media go straight to the audio system (instant, no adb). */
    fun pressKey(context: Context, code: Int, times: Int = 1, long: Boolean = false): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (code) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> {
                val dir = when (code) {
                    KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
                    KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
                    else -> AudioManager.ADJUST_TOGGLE_MUTE
                }
                repeat(times.coerceIn(1, 10)) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (Injector.key(context, code, times)) return true
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                return true
            }
        }
        if (Injector.key(context, code, times, long)) return true
        if (code == KeyEvent.KEYCODE_HOME && goHome(context)) return true
        return Shell.key(context, code, times)
    }

    fun typeText(context: Context, text: String): Boolean = Injector.text(context, text) || Shell.text(context, text)

    fun goHome(context: Context): Boolean = try {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { false }

    // ---- what's on screen right now (UsageStats: cheap, no dumpsys) ----
    private var nowAt = 0L
    private var nowPkg = ""
    fun nowPlayingPackage(context: Context): String {
        if (System.currentTimeMillis() - nowAt < 3000) return nowPkg
        nowAt = System.currentTimeMillis()
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(end - 6 * 60 * 60_000L, end)
            val e = android.app.usage.UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
            }
            if (last != null) nowPkg = last
        } catch (e: Exception) { }
        return nowPkg
    }

    fun startScreensaver(context: Context): Boolean = try {
        context.startActivity(Intent(context, za.co.dipixel.launcher.art.AmbientActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) {
        Shell.run(context, "am start -n ${context.packageName}/.art.AmbientActivity")?.contains("Error") == false
    }

    /** Next song + picture when art mode is showing. */
    fun skipAmbient(context: Context): Boolean = pressKey(context, KeyEvent.KEYCODE_DPAD_CENTER)

    // ---- shows ----
    fun playShow(context: Context, show: Show): String {
        za.co.dipixel.launcher.watch.Watching.record(context, show.title, show.providerPackage, show.url, show)
        val apps = Apps.installed(context)
        val app = apps.firstOrNull { it.packageName == show.providerPackage }
        // straight into playback: title link, profile screen, Play button (see Autoplay)
        if (show.url.isNotBlank() && app != null &&
            Agent.launch(context, za.co.dipixel.launcher.data.JustWatch.Offer(app.label, "", "FLATRATE", show.url), app, show.title)) return "Playing ${show.title}"
        if (app != null) return Voice.play(context, app, show.title)
        return Voice.handle(context, "play ${show.title}")
    }
}

/** One permanent socket per phone for remote buttons: no connection setup per press, so no lag. */
class ControlServer(private val context: Context) {
    @Volatile private var server: ServerSocket? = null

    fun start() {
        Thread {
            while (true) {
                try {
                    val ss = ServerSocket(Hub.CONTROL_PORT)
                    server = ss
                    while (true) {
                        val s = ss.accept()
                        Thread { serve(s) }.start()
                    }
                } catch (e: Exception) {
                    try { server?.close() } catch (_: Exception) {}
                    Thread.sleep(2000)   // port busy or network change: try again
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun serve(s: Socket) {
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.soTimeout = 30_000        // phones ping every 5 s
            val r = BufferedReader(InputStreamReader(s.getInputStream()))
            val out = s.getOutputStream()
            fun reply(t: String) { out.write((t + "\n").toByteArray()); out.flush() }
            val first = r.readLine() ?: return
            if (!first.startsWith("a ") || !Pairing.valid(context, first.substring(2).trim())) { reply("no"); return }
            reply("ok")
            // presses run on their own worker; anything older than 1.2 s is dropped so a hiccup can never build a backlog
            val queue = java.util.concurrent.LinkedBlockingQueue<Pair<Long, String>>()
            val worker = Thread {
                try {
                    while (true) {
                        val (at, cmd) = queue.take()
                        if (cmd == "quit") break
                        val stale = System.currentTimeMillis() - at > 1200
                        when {
                            cmd.startsWith("k ") -> if (!stale) Hub.keyCode(cmd.substring(2).trim())?.let { Hub.pressKey(context, it) }
                            cmd.startsWith("l ") -> if (!stale) Hub.keyCode(cmd.substring(2).trim())?.let { Hub.pressKey(context, it, long = true) }
                            cmd.startsWith("t ") -> Hub.typeText(context, cmd.substring(2))
                        }
                    }
                } catch (e: InterruptedException) { }
            }.apply { isDaemon = true; start() }
            try {
                while (true) {
                    val line = r.readLine() ?: break
                    if (line == "p") reply("p") else queue.offer(System.currentTimeMillis() to line)
                }
            } finally {
                queue.offer(0L to "quit")
                worker.interrupt()
            }
        } catch (e: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }
}

class HubServer(private val context: Context) : NanoHTTPD(Hub.PORT) {

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Exception) {
        json(JSONObject().put("ok", false).put("message", e.message ?: "error"), Response.Status.INTERNAL_ERROR)
    }

    private fun route(session: IHTTPSession): Response {
        val path = session.uri.trimEnd('/')
        val body: JSONObject = if (session.method == Method.POST) {
            val files = HashMap<String, String>()
            session.parseBody(files)
            try { JSONObject(files["postData"] ?: "{}") } catch (e: Exception) { JSONObject() }
        } else JSONObject()

        if (path == "/hello") {
            return json(JSONObject().put("ok", true).put("name", Hub.TV_NAME)
                .put("paired", Pairing.valid(context, session.headers["x-hub-token"])))
        }
        if (path == "/pair") {
            val token = Pairing.pair(context, body.optString("code"))
                ?: return json(JSONObject().put("ok", false).put("message", "Wrong code"), Response.Status.FORBIDDEN)
            return json(JSONObject().put("ok", true).put("token", token))
        }
        if (!Pairing.valid(context, session.headers["x-hub-token"])) {
            return json(JSONObject().put("ok", false).put("message", "Not paired"), Response.Status.UNAUTHORIZED)
        }

        val ok = JSONObject().put("ok", true)
        return when (path) {
            "/state" -> json(state())
            "/agent" -> {
                val r = Agent.ask(context, body.optString("session", "default"), body.optString("text"))
                json(JSONObject().put("ok", true).put("reply", r.text).put("cards", r.cards).put("speak", Agent.voice(context)))
            }
            "/agent/status" -> json(Agent.status(context))
            "/agent/key" -> { Agent.setKey(context, body.optString("key")); json(Agent.status(context)) }
            "/agent/subs" -> {
                val arr = body.optJSONArray("subscriptions") ?: JSONArray()
                Agent.setSubscriptions(context, (0 until arr.length()).map { arr.getString(it) }); json(Agent.status(context))
            }
            "/agent/voice" -> { Agent.setVoice(context, body.optBoolean("on", true)); json(Agent.status(context)) }
            "/agent/reset" -> { Agent.reset(body.optString("session", "default")); json(ok) }
            "/diag" -> json(JSONObject().put("ok", true)
                .put("adb", Shell.run(context, "echo ok")?.trim() == "ok")
                .put("injector", Injector.running)
                .put("injectorStart", Injector.lastStartResult)
                .put("autoplay", JSONArray(Autoplay.trace))
                .put("tune", SystemTune.lastResult)
                .put("injectorLog", Shell.run(context, "tail -n 30 ${Injector.LOG} 2>/dev/null") ?: ""))
            "/key" -> {
                val code = Hub.keyCode(body.optString("key")) ?: return json(JSONObject().put("ok", false).put("message", "Unknown key"))
                json(JSONObject().put("ok", Hub.pressKey(context, code, body.optInt("times", 1), body.optBoolean("long"))))
            }
            "/text" -> json(JSONObject().put("ok", Hub.typeText(context, body.optString("text"))))
            "/open" -> {
                val pkg = body.optString("pkg")
                val intent = context.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: context.packageManager.getLaunchIntentForPackage(pkg)
                json(JSONObject().put("ok", Voice.start(context, intent)))
            }
            "/say" -> json(ok.put("message", Voice.handle(context, body.optString("text"))))
            "/profile" -> {
                val i = body.optInt("index")
                ConfigStore.update(context) { it.copy(activeProfile = i.coerceIn(0, it.profiles.lastIndex)) }
                json(ok)
            }
            "/night" -> { ConfigStore.update(context) { it.copy(nightMode = body.optBoolean("on")) }; json(ok) }
            "/sleep" -> {
                val m = body.optInt("minutes")
                if (m <= 0) { Sleep.cancel(context); ConfigStore.update(context) { it.copy(sleepAt = 0L) } }
                else { Sleep.schedule(context, m); ConfigStore.update(context) { it.copy(sleepAt = System.currentTimeMillis() + m * 60_000L) } }
                json(ok)
            }
            "/screensaver" -> json(JSONObject().put("ok", Hub.startScreensaver(context)))
            "/screensaver/skip" -> json(JSONObject().put("ok", Hub.skipAmbient(context)))
            "/lofi" -> {
                ConfigStore.update(context) { c ->
                    c.copy(lofiRadio = if (body.has("on")) body.optBoolean("on", true) else c.lofiRadio,
                        lofiLevel = if (body.has("level")) body.optInt("level", 2).coerceIn(1, 3) else c.lofiLevel)
                }
                za.co.dipixel.launcher.art.Ambient.applyLevelLive(ConfigStore.load(context).lofiLevel)
                json(ok)
            }
            "/art/refresh" -> { ArtRepo.refreshNow(context); json(ok) }
            "/show/add" -> {
                val show = Show(
                    title = body.optString("title"),
                    providerPackage = body.optString("pkg"),
                    providerLabel = body.optString("label"),
                    url = body.optString("url"),
                    backdrop = body.optString("backdrop"),
                    poster = body.optString("poster"),
                    summary = body.optString("summary"),
                    meta = body.optString("meta")
                )
                if (show.title.isBlank()) return json(JSONObject().put("ok", false).put("message", "No title"))
                val profile = body.optInt("profile", ConfigStore.load(context).activeProfile)
                val full = Artwork.enrich(show)
                ConfigStore.update(context) { c ->
                    c.copy(profiles = c.profiles.mapIndexed { i, p -> if (i == profile) p.copy(shows = listOf(full) + p.shows.filter { it.title != full.title }) else p })
                }
                if (body.optBoolean("play")) Hub.playShow(context, full)
                json(ok)
            }
            "/show/remove" -> {
                val profile = body.optInt("profile"); val index = body.optInt("index")
                ConfigStore.update(context) { c ->
                    c.copy(profiles = c.profiles.mapIndexed { i, p -> if (i == profile) p.copy(shows = p.shows.filterIndexed { j, _ -> j != index }) else p })
                }
                json(ok)
            }
            "/show/play" -> {
                val c = ConfigStore.load(context)
                val show = c.profiles.getOrNull(body.optInt("profile", c.activeProfile))?.shows?.getOrNull(body.optInt("index"))
                    ?: return json(JSONObject().put("ok", false).put("message", "No such show"))
                json(ok.put("message", Hub.playShow(context, show)))
            }
            "/apps" -> {
                val all = Apps.installed(context)
                val home = favouriteApps(ConfigStore.load(context), all)
                json(JSONObject().put("ok", true).put("home", appsJson(home)).put("all", appsJson(all)))
            }
            "/icon" -> {
                val pkg = session.parameters["pkg"]?.firstOrNull() ?: ""
                val app = Apps.installed(context).firstOrNull { it.packageName == pkg }
                    ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no app")
                val d = app.banner ?: app.icon
                val w = if (d.intrinsicWidth > 0) d.intrinsicWidth.coerceAtMost(320) else 160
                val h = if (d.intrinsicHeight > 0) d.intrinsicHeight.coerceAtMost(180) else 160
                val out = ByteArrayOutputStream()
                d.toBitmap(w, h).compress(Bitmap.CompressFormat.PNG, 100, out)
                val bytes = out.toByteArray()
                newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
                    addHeader("Cache-Control", "max-age=86400")
                }
            }
            else -> json(JSONObject().put("ok", false).put("message", "Unknown path"), Response.Status.NOT_FOUND)
        }
    }

    private fun state(): JSONObject {
        val c = ConfigStore.load(context)
        val all = Apps.installed(context)
        val nowPkg = Hub.nowPlayingPackage(context)
        val now = all.firstOrNull { it.packageName == nowPkg }
        val profile = c.profiles.getOrNull(c.activeProfile) ?: c.profiles.first()
        return JSONObject()
            .put("ok", true)
            .put("name", Hub.TV_NAME)
            .put("profiles", JSONArray(c.profiles.map { it.name }))
            .put("active", c.activeProfile)
            .put("night", c.nightMode)
            .put("lofi", c.lofiRadio)
            .put("lofiLevel", c.lofiLevel)
            .put("sleepAt", c.sleepAt)
            .put("now", if (now != null) JSONObject().put("pkg", now.packageName).put("label", now.label) else JSONObject.NULL)
            .put("onHome", nowPkg == context.packageName)
            .put("shows", JSONArray().apply { profile.shows.forEach { put(it.toJson()) } })
            .put("home", appsJson(favouriteApps(c, all)))
            .put("keys", Injector.running)
    }

    private fun appsJson(list: List<TvApp>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("pkg", it.packageName).put("label", it.label)) }
    }

    private fun json(o: JSONObject, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json", o.toString())
}
