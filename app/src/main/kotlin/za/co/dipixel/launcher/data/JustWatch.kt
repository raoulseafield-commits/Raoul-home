package za.co.dipixel.launcher.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Where can we watch it in South Africa? JustWatch's public GraphQL, no key. */
object JustWatch {

    data class Offer(val service: String, val technical: String, val type: String, val url: String)
    data class Title(
        val id: String, val kind: String, val title: String, val year: Int,
        val poster: String, val description: String, val offers: List<Offer>
    ) {
        /** Streaming offers (subscription or free), one per service. */
        val streaming: List<Offer> get() = offers.filter { it.type in setOf("FLATRATE", "FREE", "ADS") }.distinctBy { it.service }
        val rentBuy: List<Offer> get() = offers.filter { it.type in setOf("RENT", "BUY") }.distinctBy { it.service }
    }

    private const val Q = """
query Search(${'$'}country: Country!, ${'$'}language: Language!, ${'$'}first: Int!, ${'$'}q: String!) {
  popularTitles(country: ${'$'}country, first: ${'$'}first, filter: {searchQuery: ${'$'}q}) {
    edges { node {
      id objectType
      content(country: ${'$'}country, language: ${'$'}language) { title originalReleaseYear posterUrl shortDescription }
      offers(country: ${'$'}country, platform: WEB) { monetizationType standardWebURL package { clearName technicalName } }
    } }
  }
}"""

    fun search(query: String, country: String = "ZA", first: Int = 8): List<Title> = try {
        val body = JSONObject().put("query", Q).put("variables", JSONObject()
            .put("country", country).put("language", "en").put("first", first).put("q", query))
        val c = (URL("https://apis.justwatch.com/graphql").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 8000; readTimeout = 12000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "MiTV/1.0")
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val edges = JSONObject(text).optJSONObject("data")?.optJSONObject("popularTitles")?.optJSONArray("edges") ?: JSONArray()
        (0 until edges.length()).map { i ->
            val n = edges.getJSONObject(i).getJSONObject("node")
            val ct = n.optJSONObject("content") ?: JSONObject()
            val offs = n.optJSONArray("offers") ?: JSONArray()
            val poster = ct.optString("posterUrl").let {
                if (it.isBlank() || it == "null") "" else "https://images.justwatch.com" + it.replace("{profile}", "s592").replace("{format}", "jpg")
            }
            Title(
                id = n.optString("id"),
                kind = if (n.optString("objectType") == "SHOW") "show" else "movie",
                title = ct.optString("title"),
                year = ct.optInt("originalReleaseYear"),
                poster = poster,
                description = ct.optString("shortDescription"),
                offers = (0 until offs.length()).map { j ->
                    val o = offs.getJSONObject(j); val p = o.optJSONObject("package") ?: JSONObject()
                    Offer(p.optString("clearName"), p.optString("technicalName"), o.optString("monetizationType"), o.optString("standardWebURL"))
                }
            )
        }
    } catch (e: Exception) { emptyList() }
}
