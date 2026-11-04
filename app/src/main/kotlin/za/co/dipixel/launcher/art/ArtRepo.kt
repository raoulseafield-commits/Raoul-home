package za.co.dipixel.launcher.art

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

data class Art(val title: String, val artist: String, val file: File? = null, val asset: String? = null, val mood: String = "")

/**
 * Public domain paintings from the Art Institute of Chicago open API (no key, no account).
 * New pieces are cached on the box, so the screensaver keeps working when the Wi-Fi is down.
 * A starter set is baked into the APK at build time (assets/art).
 */
object ArtRepo {
    private const val MAX_CACHED = 120
    private const val REFRESH_MS = 12 * 60 * 60 * 1000L
    private val topics = listOf(
        "landscape", "impressionism", "seascape", "mountains", "garden", "river",
        "sunset", "japanese woodblock", "harbor", "forest", "snow", "city view", "flowers"
    )
    @Volatile private var running = false
    /** Hand-picked masterpieces only (no random museum downloads). */
    private const val CURATED_ONLY = true

    private fun baked(context: Context): List<Art> = try {
        val arr = JSONArray(context.assets.open("art/index.json").bufferedReader().use { it.readText() })
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { Art(it.optString("title"), it.optString("artist"), asset = it.getString("file"), mood = it.optString("mood")) }
    } catch (e: Exception) { emptyList() }

    /** Picture mood for right now: season first (Halloween, Christmas), then the weather. */
    fun moodFor(weatherCode: Int?, isDay: Boolean?, date: java.time.LocalDate = java.time.LocalDate.now()): String {
        if (date.monthValue == 10 && date.dayOfMonth >= 20) return "halloween"
        if (date.monthValue == 12 && date.dayOfMonth in 10..31) return "christmas"
        return when (weatherCode) {
            null -> "any"
            in 51..67, in 80..82 -> "rain"
            95, 96, 99 -> "storm"
            in 71..77, 85, 86 -> "snow"
            45, 48 -> "fog"
            2, 3 -> "cloudy"
            else -> if (isDay == false) "night" else "sunny"
        }
    }

    private val moodTopics = mapOf(
        "halloween" to listOf("autumn", "night moon", "witch", "harvest pumpkin", "autumn landscape"),
        "christmas" to listOf("winter snow village", "snow landscape", "winter night"),
        "rain" to listOf("rain", "rainy street", "storm sea"),
        "storm" to listOf("storm", "storm sea", "lightning"),
        "snow" to listOf("snow", "winter landscape"),
        "fog" to listOf("fog", "mist landscape", "tonalism"),
        "cloudy" to listOf("clouds", "cloudy landscape", "overcast sea"),
        "night" to listOf("moonlight", "night landscape", "nocturne"),
        "sunny" to listOf("summer landscape", "garden", "sunlight", "impressionism")
    )

    /** Make sure there are pictures for this mood (downloads a batch in the background if not). */
    fun ensureMood(context: Context, mood: String) {
        if (CURATED_ONLY || mood == "any" || running) return
        if (all(context).count { it.mood == mood } >= 6) return
        running = true
        val app = context.applicationContext
        Thread {
            try { fetchBatch(app, 8, moodTopics[mood]?.random() ?: "landscape", mood) } catch (e: Exception) { }
            running = false
        }.start()
    }

    private fun dir(context: Context) = File(context.filesDir, "art").apply { mkdirs() }
    private fun indexFile(context: Context) = File(dir(context), "index.json")

    fun all(context: Context): List<Art> {
        val out = ArrayList<Art>()
        if (CURATED_ONLY) return baked(context)
        // downloaded
        try {
            val arr = JSONArray(indexFile(context).readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val f = File(dir(context), o.getString("file"))
                if (f.exists() && f.length() > 10_000) out += Art(o.optString("title"), o.optString("artist"), file = f, mood = o.optString("mood"))
            }
        } catch (e: Exception) { }
        // baked in
        try {
            val arr = JSONArray(context.assets.open("art/index.json").bufferedReader().use { it.readText() })
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Art(o.optString("title"), o.optString("artist"), asset = o.getString("file"))
            }
        } catch (e: Exception) { }
        return out
    }

    fun cachedCount(context: Context): Int = try { JSONArray(indexFile(context).readText()).length() } catch (e: Exception) { 0 }

    fun refreshIfDue(context: Context) {
        if (CURATED_ONLY) return
        val p = context.getSharedPreferences("art", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong("last", 0L) < REFRESH_MS) return
        refreshNow(context)
    }

    fun refreshNow(context: Context, onDone: ((Int) -> Unit)? = null) {
        if (CURATED_ONLY) { onDone?.invoke(0); return }
        if (running) return
        running = true
        val app = context.applicationContext
        Thread {
            var added = 0
            try {
                added = fetchBatch(app, 8)
                if (added > 0) app.getSharedPreferences("art", Context.MODE_PRIVATE).edit().putLong("last", System.currentTimeMillis()).apply()
            } catch (e: Exception) { }
            running = false
            onDone?.invoke(added)
        }.start()
    }

    private fun fetchBatch(context: Context, want: Int, forcedTopic: String? = null, mood: String = ""): Int {
        val topic = forcedTopic ?: topics[Random.nextInt(topics.size)]
        val page = Random.nextInt(1, 8)
        val url = "https://api.artic.edu/api/v1/artworks/search?q=" + URLEncoder.encode(topic, "UTF-8") +
            "&query%5Bterm%5D%5Bis_public_domain%5D=true" +
            "&fields=id,title,artist_title,image_id,thumbnail&limit=40&page=$page"
        val json = JSONObject(get(url).toString(Charsets.UTF_8))
        val data = json.optJSONArray("data") ?: return 0

        val index = try { JSONArray(indexFile(context).readText()) } catch (e: Exception) { JSONArray() }
        val have = HashSet<String>().apply { for (i in 0 until index.length()) add(index.getJSONObject(i).optString("id")) }
        var added = 0
        for (i in 0 until data.length()) {
            if (added >= want) break
            val o = data.getJSONObject(i)
            val imageId = o.optString("image_id")
            if (imageId.isBlank() || imageId == "null") continue
            val id = o.optString("id")
            if (id in have) continue
            val th = o.optJSONObject("thumbnail")
            val w = th?.optDouble("width", 0.0) ?: 0.0
            val h = th?.optDouble("height", 0.0) ?: 0.0
            if (w <= 0 || h <= 0 || w / h < 1.25) continue   // landscape pieces only, they fill a TV
            try {
                val bytes = get("https://www.artic.edu/iiif/2/$imageId/full/1686,/0/default.jpg")
                if (bytes.size < 30_000) continue
                val name = "$id.jpg"
                File(dir(context), name).writeBytes(bytes)
                index.put(JSONObject().put("id", id).put("title", o.optString("title")).put("artist", o.optString("artist_title")).put("file", name).put("mood", mood))
                have += id
                added++
            } catch (e: Exception) { }
        }
        // keep the newest MAX_CACHED
        while (index.length() > MAX_CACHED) {
            val old = index.getJSONObject(0)
            File(dir(context), old.optString("file")).delete()
            index.remove(0)
        }
        indexFile(context).writeText(index.toString())
        return added
    }

    private fun get(url: String): ByteArray {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("AIC-User-Agent", "DipixelHome screensaver")
            setRequestProperty("User-Agent", "DipixelHome/1.0")
        }
        return c.inputStream.use { it.readBytes() }.also { c.disconnect() }
    }
}
