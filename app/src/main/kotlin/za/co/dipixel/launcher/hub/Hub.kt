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

    /** Press a key on the TV: instant injector first, adb command second, local fallbacks last. */
    fun pressKey(context: Context, code: Int, times: Int = 1, long: Boolean = false): Boolean {
        if (Injector.key(context, code, times, long)) return true
        if (Shell.key(context, code, times)) return true
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (code) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> {
                val dir = when (code) {
                    KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
                    KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
                    else -> AudioManager.ADJUST_TOGGLE_MUTE
                }
                repeat(times) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                return true
            }
            KeyEvent.KEYCODE_HOME -> return goHome(context)
        }
        return false
    }

    fun typeText(context: Context, text: String): Boolean = Injector.text(context, text) || Shell.text(context, text)

    fun goHome(context: Context): Boolean = try {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { false }

    // ---- what's on screen right now (cached, it costs a dumpsys) ----
    private var nowAt = 0L
    private var nowPkg = ""
    fun nowPlayingPackage(context: Context): String {
        if (System.currentTimeMillis() - nowAt < 4000) return nowPkg
        nowAt = System.currentTimeMillis()
        val out = Shell.run(context, "dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'") ?: return nowPkg
        nowPkg = Regex("u0 ([a-zA-Z0-9_.]+)/").find(out)?.groupValues?.get(1) ?: nowPkg
        return nowPkg
    }

    fun startScreensaver(context: Context): Boolean =
        Shell.run(context, "am start -n com.android.systemui/.Somnambulator")?.contains("Error") == false

    // ---- shows ----
    fun playShow(context: Context, show: Show): String {
        val apps = Apps.installed(context)
        val app = apps.firstOrNull { it.packageName == show.providerPackage }
        if (show.url.isNotBlank()) {
            val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(show.url))
            if (show.providerPackage.isNotBlank()) i.setPackage(show.providerPackage)
            if (Voice.start(context, i)) return "Opening ${show.title}"
        }
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
            while (true) {
                val line = r.readLine() ?: break
                when {
                    line == "p" -> reply("p")
                    line.startsWith("k ") -> Hub.keyCode(line.substring(2).trim())?.let { Hub.pressKey(context, it) }
                    line.startsWith("l ") -> Hub.keyCode(line.substring(2).trim())?.let { Hub.pressKey(context, it, long = true) }
                    line.startsWith("t ") -> Hub.typeText(context, line.substring(2))
                }
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
