package za.co.dipixel.launcher.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import org.json.JSONArray
import za.co.dipixel.launcher.data.ConfigStore
import za.co.dipixel.launcher.data.Wallpapers
import za.co.dipixel.launcher.data.Weather
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * The art + lofi radio experience, shared by the system screensaver and the "Art" button.
 * OK or Right: new picture and new song (pixel dissolve). Left: previous picture. Back: leave.
 * Time and weather sit small in the bottom right, the song small in the bottom left.
 */
class Ambient(private val context: Context, private val withRadio: Boolean) {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var image: ImageView
    private lateinit var song: TextView
    private lateinit var hint: TextView
    private var weatherView: TextView? = null
    private var queue: MutableList<Art> = mutableListOf()
    private val history = ArrayList<Art>()
    private var current: Bitmap? = null
    private var mood = "any"
    private var fallback = 0
    private val slideMs = 90_000L
    private var player: ExoPlayer? = null
    private var stations: List<Radio.Station> = emptyList()
    private var stationIndex = Random.nextInt(0, 1000)

    fun build(): View {
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x66000000, 0x00000000))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140), Gravity.BOTTOM))

        // bottom right: time · weather
        val corner = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, dp(40), dp(30)) }
        corner.addView(TextClock(context).apply {
            format24Hour = "H:mm"; format12Hour = "h:mm"; setTextColor(0xE6FFFFFF.toInt()); textSize = 18f; setShadowLayer(8f, 0f, 1f, 0xAA000000.toInt())
        })
        weatherView = TextView(context).apply { setTextColor(0xCCFFFFFF.toInt()); textSize = 15f; setShadowLayer(8f, 0f, 1f, 0xAA000000.toInt()); setPadding(dp(10), 0, 0, 0) }
        corner.addView(weatherView)
        root.addView(corner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))

        // bottom left: lofi radio · song
        song = TextView(context).apply {
            setTextColor(0xB3FFFFFF.toInt()); textSize = 13f; setShadowLayer(8f, 0f, 1f, 0xAA000000.toInt())
            setPadding(dp(40), 0, 0, dp(32)); maxLines = 1; visibility = if (withRadio) View.VISIBLE else View.GONE
            text = " ♪  lofi radio"
        }
        root.addView(song, FrameLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))

        hint = TextView(context).apply {
            setTextColor(0xE6FFFFFF.toInt()); textSize = 14f; setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0x8C000000.toInt()) }
            text = "OK  next song + picture    ←  previous picture    Back  exit"
        }
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(28) })
        return root
    }

    fun start() {
        handler.postDelayed({ hint.animate().alpha(0f).setDuration(800).start() }, 6000)
        Thread {
            val c = ConfigStore.load(context)
            val w = kotlinx.coroutines.runBlocking { Weather.fetch(c.latitude, c.longitude) }
            mood = ArtRepo.moodFor(w?.code, w?.isDay)
            ArtRepo.ensureMood(context, mood)
            handler.post { if (w != null) weatherView?.text = "·  ${w.tempC}°  ${w.description}" }
        }.start()
        ArtRepo.refreshIfDue(context)
        showNext(transition = false)
        if (withRadio) startRadio()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        player?.release(); player = null
        if (live === this) live = null
        restoreVolume()
    }

    // ---------------- soft volume ----------------
    // The radio must always be gentle, even if the TV was left loud for a movie.
    // 1) the box's own output is lowered while the art plays (and put back after),
    // 2) the radio itself plays at a fraction of that, and fades in.
    // When the box sends fixed volume over HDMI (TV controls the level), step 2 alone keeps it soft.

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    private var savedVolume = -1
    private var setVolume = -1
    private var gain = 0.25f

    private fun levelGain(level: Int) = when (level) { 1 -> 0.12f; 3 -> 0.4f; else -> 0.22f }

    private fun duckVolume() {
        try {
            if (audio.isVolumeFixed) return
            val stream = android.media.AudioManager.STREAM_MUSIC
            val max = audio.getStreamMaxVolume(stream)
            val cur = audio.getStreamVolume(stream)
            val target = minOf(cur, kotlin.math.ceil(max * 0.4).toInt())
            if (target < cur) {
                savedVolume = cur; setVolume = target
                audio.setStreamVolume(stream, target, 0)
            }
        } catch (e: Exception) { }
    }

    private fun restoreVolume() {
        try {
            if (savedVolume < 0) return
            val stream = android.media.AudioManager.STREAM_MUSIC
            // only put it back if nobody changed the volume while the art was playing
            if (audio.getStreamVolume(stream) == setVolume) audio.setStreamVolume(stream, savedVolume, 0)
        } catch (e: Exception) { }
        savedVolume = -1; setVolume = -1
    }

    private fun fadeIn(p: ExoPlayer) {
        p.volume = 0f
        val steps = 20
        for (i in 1..steps) handler.postDelayed({ if (player === p) p.volume = gain * i / steps }, i * 200L)
    }

    companion object {
        @Volatile private var live: Ambient? = null
        /** Called when the level is changed from Mi Remote while the art is on screen. */
        fun applyLevelLive(level: Int) {
            val a = live ?: return
            a.handler.post { a.gain = a.levelGain(level); a.player?.volume = a.gain }
        }
    }

    fun handles(keyCode: Int) = keyCode in setOf(
        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
        android.view.KeyEvent.KEYCODE_MEDIA_NEXT, android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

    /** Remote keys. Returns true when handled; false means "leave". */
    fun onKey(keyCode: Int): Boolean = when (keyCode) {
        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> { skip(); true }
        android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { previous(); true }
        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { player?.let { if (it.isPlaying) it.pause() else it.play() }; true }
        android.view.KeyEvent.KEYCODE_VOLUME_UP, android.view.KeyEvent.KEYCODE_VOLUME_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_MUTE -> false
        else -> false
    }

    fun skip() { showNext(transition = true); if (withRadio) nextStation() }

    private fun previous() {
        if (history.size < 2) return
        history.removeAt(history.lastIndex)
        val art = history.removeAt(history.lastIndex)
        queue.add(0, art)
        showNext(transition = true)
    }

    // ---------------- pictures ----------------

    private val tick = Runnable { showNext(transition = true) }

    private fun pickNext(): Art? {
        if (queue.isEmpty()) {
            val all = ArtRepo.all(context)
            val family = when (mood) { "halloween" -> setOf("halloween", "night", "storm"); "christmas" -> setOf("snow"); "storm" -> setOf("storm", "rain"); else -> setOf(mood) }
            val matching = all.filter { it.mood in family }
            // mostly pictures that match the weather/season, sprinkled with the rest
            queue = (matching.shuffled() + all.filter { it.mood !in family }.shuffled().take(maxOf(3, matching.size / 2))).toMutableList()
            if (queue.isEmpty()) return null
        }
        return queue.removeAt(0)
    }

    private fun showNext(transition: Boolean) {
        handler.removeCallbacks(tick)
        val art = pickNext()
        Thread {
            val bmp = (art?.let { load(it) } ?: fallbackBitmap()) ?: return@Thread
            handler.post {
                if (art != null) history += art
                if (transition && current != null) pixelSwap(current!!, bmp) else setBitmap(bmp, false)
                current = bmp
            }
        }.start()
        handler.postDelayed(tick, slideMs)
    }

    private fun setBitmap(b: Bitmap, pixelated: Boolean) {
        image.setImageDrawable(BitmapDrawable(context.resources, b).apply { isFilterBitmap = !pixelated })
    }

    /** Retro pixel dissolve: the old picture breaks into blocks, the new one resolves out of them. */
    private fun pixelSwap(from: Bitmap, to: Bitmap) {
        var t = 0L
        fun step(b: Bitmap, block: Int) {
            handler.postDelayed({ setBitmap(Bitmap.createScaledBitmap(b, maxOf(1, b.width / block), maxOf(1, b.height / block), false), true) }, t)
            t += 70
        }
        for (block in listOf(8, 16, 32, 64)) step(from, block)   // break up
        for (block in listOf(64, 32, 16, 8)) step(to, block)     // resolve
        handler.postDelayed({ setBitmap(to, false) }, t)
    }

    private fun load(art: Art): Bitmap? = try {
        if (art.file != null) decode { art.file.inputStream() } else decode { context.assets.open(art.asset!!) }
    } catch (e: Exception) { null }

    private fun fallbackBitmap(): Bitmap? {
        val name = Wallpapers.ALL[fallback++ % Wallpapers.ALL.size]
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        return if (id == 0) null else BitmapFactory.decodeResource(context.resources, id)
    }

    private fun decode(open: () -> InputStream): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1920) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        return open().use { BitmapFactory.decodeStream(it, null, opts) }
    }

    // ---------------- lofi radio ----------------

    private fun startRadio() {
        val p = ExoPlayer.Builder(context).build()
        player = p
        live = this
        gain = levelGain(ConfigStore.load(context).lofiLevel)
        duckVolume()
        fadeIn(p)
        p.addListener(object : Player.Listener {
            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val e = metadata.get(i)
                    if (e is IcyInfo && !e.title.isNullOrBlank()) song.text = "♪  " + e.title
                }
            }
            override fun onPlayerError(error: PlaybackException) { handler.postDelayed({ nextStation() }, 800) }
        })
        Thread {
            stations = Radio.stations(context)
            handler.post { playStation() }
        }.start()
    }

    private fun nextStation() { stationIndex++; playStation() }

    private fun playStation() {
        val p = player ?: return
        if (stations.isEmpty()) { song.text = "♪  lofi radio offline"; return }
        val st = stations[Math.floorMod(stationIndex, stations.size)]
        song.text = "♪  ${st.name}"
        p.setMediaItem(MediaItem.fromUri(st.url))
        p.prepare()
        p.play()
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}

/** Free lofi stations: Radio Browser directory (refreshed weekly) plus tested fallbacks. */
object Radio {
    data class Station(val name: String, val url: String)

    val FALLBACK = listOf(
        Station("#lofi by REYFM", "https://listen.reyfm.de/lofi_320kbps.mp3"),
        Station("I Love Chillhop", "https://ilm.stream12.radiohost.de/ilm_ilovechillhop_mp3-192"),
        Station("Chillhop Radio", "https://streams.fluxfm.de/Chillhop/mp3-128/streams.fluxfm.de/"),
        Station("laut.fm Lofi", "https://stream.laut.fm/lofi"),
        Station("ISEKOI Chill Zone", "https://public.isekoi-radio.com/listen/chill/radio.mp3"),
        Station("NIA Lo-Fi", "https://radio.nia.nc/radio/8020/lofi-hq-stream.aac"),
        Station("Hotmix Lofi", "https://streaming.hotmixradio.com/hotmix-lofi-en-mp3"),
        Station("Cliamp Lofi", "https://radio.cliamp.stream/lofi/stream"),
        Station("0nlineradio Lo-Fi", "https://stream.0nlineradio.com/lo-fi"),
        Station("SomaFM Fluid", "https://ice1.somafm.com/fluid-128-mp3")
    )

    fun stations(context: Context): List<Station> {
        val p = context.getSharedPreferences("radio", Context.MODE_PRIVATE)
        val cached = p.getString("list", null)
        if (cached != null && System.currentTimeMillis() - p.getLong("at", 0) < 7 * 24 * 3600_000L) return parse(cached).ifEmpty { FALLBACK }
        return try {
            val c = (URL("https://de1.api.radio-browser.info/json/stations/bytag/lofi?order=votes&reverse=true&limit=40&hidebroken=true")
                .openConnection() as HttpURLConnection).apply { connectTimeout = 6000; readTimeout = 8000; setRequestProperty("User-Agent", "MiTV/1.0") }
            val text = c.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optString("codec") in setOf("MP3", "AAC", "AAC+") && it.optInt("bitrate") >= 64 && it.optInt("lastcheckok") == 1 }
                .filter { Regex("lo-?fi|chill", RegexOption.IGNORE_CASE).containsMatchIn(it.optString("name")) }
                .map { Station(it.optString("name").trim().take(40), it.optString("url_resolved")) }
                .filter { it.url.startsWith("http") }
                .take(20)
            val all = (FALLBACK + list).distinctBy { it.url }
            p.edit().putString("list", JSONArray().apply { all.forEach { put(org.json.JSONObject().put("n", it.name).put("u", it.url)) } }.toString())
                .putLong("at", System.currentTimeMillis()).apply()
            all
        } catch (e: Exception) { cached?.let { parse(it) }?.ifEmpty { null } ?: FALLBACK }
    }

    private fun parse(s: String): List<Station> = try {
        val a = JSONArray(s); (0 until a.length()).map { Station(a.getJSONObject(it).getString("n"), a.getJSONObject(it).getString("u")) }
    } catch (e: Exception) { emptyList() }
}
