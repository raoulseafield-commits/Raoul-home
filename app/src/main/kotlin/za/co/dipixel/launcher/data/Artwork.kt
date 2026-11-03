package za.co.dipixel.launcher.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Finds a backdrop, poster and blurb for a title. No API keys:
 * TVmaze for TV shows (has landscape backdrops), iTunes Search for movies.
 */
object Artwork {

    data class Found(val title: String, val backdrop: String, val poster: String, val summary: String, val meta: String, val kind: String)

    fun resolve(title: String): Found? = tvShow(title) ?: movie(title)

    fun tvShow(title: String): Found? = try {
        val o = JSONObject(get("https://api.tvmaze.com/singlesearch/shows?q=" + enc(title)))
        val id = o.getInt("id")
        val poster = o.optJSONObject("image")?.optString("original").orEmpty()
        var backdrop = ""
        try {
            val imgs = JSONArray(get("https://api.tvmaze.com/shows/$id/images"))
            var best: String? = null
            for (i in 0 until imgs.length()) {
                val im = imgs.getJSONObject(i)
                if (im.optString("type") == "background") {
                    val url = im.optJSONObject("resolutions")?.optJSONObject("original")?.optString("url")
                    if (!url.isNullOrBlank()) { if (im.optBoolean("main") || best == null) best = url }
                }
            }
            backdrop = best ?: ""
        } catch (e: Exception) { }
        val year = o.optString("premiered").take(4)
        val genres = o.optJSONArray("genres")?.let { g -> (0 until g.length()).joinToString(", ") { g.getString(it) } }.orEmpty()
        val network = (o.optJSONObject("webChannel") ?: o.optJSONObject("network"))?.optString("name").orEmpty()
        Found(
            title = o.optString("name", title),
            backdrop = backdrop,
            poster = poster,
            summary = stripHtml(o.optString("summary")),
            meta = listOf(year, network, genres).filter { it.isNotBlank() }.joinToString("  ·  "),
            kind = "show"
        )
    } catch (e: Exception) { null }

    fun movie(title: String): Found? = try {
        val r = JSONObject(get("https://itunes.apple.com/search?media=movie&entity=movie&limit=1&term=" + enc(title)))
        val m = r.getJSONArray("results").getJSONObject(0)
        val art = m.optString("artworkUrl100").replace("100x100bb", "1000x1500bb")
        Found(
            title = m.optString("trackName", title),
            backdrop = "",
            poster = art,
            summary = m.optString("longDescription", m.optString("shortDescription")),
            meta = listOf(m.optString("releaseDate").take(4), m.optString("primaryGenreName"), m.optString("contentAdvisoryRating"))
                .filter { it.isNotBlank() }.joinToString("  ·  "),
            kind = "movie"
        )
    } catch (e: Exception) { null }

    /** Fill in missing artwork for a show, keeping anything already set. */
    fun enrich(show: Show): Show {
        if (show.backdrop.isNotBlank() && show.poster.isNotBlank() && show.summary.isNotBlank()) return show
        val f = resolve(show.title) ?: return show
        return show.copy(
            backdrop = show.backdrop.ifBlank { f.backdrop },
            poster = show.poster.ifBlank { f.poster },
            summary = show.summary.ifBlank { f.summary },
            meta = show.meta.ifBlank { f.meta }
        )
    }

    private fun stripHtml(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").trim()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000
            setRequestProperty("User-Agent", "MiTV/1.0")
        }
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }
}
