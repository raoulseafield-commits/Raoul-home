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
import za.co.dipixel.launcher.data.Apps
import za.co.dipixel.launcher.data.ConfigStore
import za.co.dipixel.launcher.ui.favouriteApps
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

object Hub {
    const val PORT = 8765

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
        "delete" to KeyEvent.KEYCODE_DEL
    )

    fun keyCode(name: String): Int? = keyNames[name.lowercase()]

    /** Press a key on the TV. adb first (works inside every app), local fallbacks for the basics. */
    fun pressKey(context: Context, code: Int, times: Int = 1): Boolean {
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
            KeyEvent.KEYCODE_HOME -> {
                return try {
                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
                } catch (e: Exception) { false }
            }
        }
        return false
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
            return json(JSONObject().put("ok", true).put("name", "Dipixel Home")
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

        return when (path) {
            "/key" -> {
                val code = Hub.keyCode(body.optString("key"))
                    ?: return json(JSONObject().put("ok", false).put("message", "Unknown key"))
                val ok = Hub.pressKey(context, code, body.optInt("times", 1))
                json(JSONObject().put("ok", ok).put("adb", Shell.lastOk))
            }
            "/text" -> json(JSONObject().put("ok", Shell.text(context, body.optString("text"))))
            "/open" -> {
                val pkg = body.optString("pkg")
                val intent = context.packageManager.getLeanbackLaunchIntentForPackage(pkg)
                    ?: context.packageManager.getLaunchIntentForPackage(pkg)
                json(JSONObject().put("ok", Voice.start(context, intent)))
            }
            "/say" -> {
                val msg = Voice.handle(context, body.optString("text"))
                json(JSONObject().put("ok", true).put("message", msg))
            }
            "/apps" -> {
                val all = Apps.installed(context)
                val home = favouriteApps(ConfigStore.load(context), all)
                fun arr(list: List<za.co.dipixel.launcher.data.TvApp>) = JSONArray().apply {
                    list.forEach { put(JSONObject().put("pkg", it.packageName).put("label", it.label)) }
                }
                json(JSONObject().put("ok", true).put("home", arr(home)).put("all", arr(all)))
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
                newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong())
            }
            else -> json(JSONObject().put("ok", false).put("message", "Unknown path"), Response.Status.NOT_FOUND)
        }
    }

    private fun json(o: JSONObject, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json", o.toString())
}
