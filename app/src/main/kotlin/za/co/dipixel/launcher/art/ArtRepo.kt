package za.co.dipixel.launcher.art

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

data class Art(val title: String, val artist: String, val file: File? = null, val asset: String? = null)

/**
 * Public domain paintings from the Art Institute of Chicago open API (no key, no account).
 * New pieces are cached on the box, so the screensaver keeps working when the Wi-Fi is down.
 * A starter set is baked into the APK at build time (assets/art).
 */
object ArtRepo {
    private const val MAX_CACHED = 60
    private const val REFRESH_MS = 12 * 60 * 60 * 1000L
    private val topics = listOf(
        "landscape", "impressionism", "seascape", "mountains", "garden", "river",
        "sunset", "japanese woodblock", "harbor", "forest", "snow", "city view", "flowers"
    )
    @Volatile private var running = false

    private fun dir(context: Context) = File(context.filesDir, "art").apply { mkdirs() }
    private fun indexFile(context: Context) = File(dir(context), "index.json")

    fun all(context: Context): List<Art> {
        val out = ArrayList<Art>()
        // downloaded
        try {
            val arr = JSONArray(indexFile(context).readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val f = File(dir(context), o.getString("file"))
                if (f.exists() && f.length() > 10_000) out += Art(o.optString("title"), o.optString("artist"), file = f)
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
        val p = context.getSharedPreferences("art", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong("last", 0L) < REFRESH_MS) return
        refreshNow(context)
    }

    fun refreshNow(context: Context, onDone: ((Int) -> Unit)? = null) {
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

    private fun fetchBatch(context: Context, want: Int): Int {
        val topic = topics[Random.nextInt(topics.size)]
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
                index.put(JSONObject().put("id", id).put("title", o.optString("title")).put("artist", o.optString("artist_title")).put("file", name))
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
