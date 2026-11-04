package za.co.dipixel.launcher.hub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import za.co.dipixel.launcher.data.Apps
import za.co.dipixel.launcher.data.ConfigStore
import za.co.dipixel.launcher.data.JustWatch
import za.co.dipixel.launcher.data.Show
import za.co.dipixel.launcher.data.TvApp
import za.co.dipixel.launcher.night.Sleep
import za.co.dipixel.launcher.watch.Watching
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * The Mi TV assistant. "What's the latest Superman movie?" -> finds it, checks South African
 * streaming against the household's subscriptions -> "It's on Netflix, want me to play it?" -> "Play it".
 * Brain: Google Gemini (free key, stored on the TV). Without a key a rule based version still works.
 */
object Agent {
    private const val PREFS = "agent"
    private val MODELS = listOf("gemini-flash-latest", "gemini-2.5-flash", "gemini-2.0-flash")

    private class Session {
        var contents = JSONArray()
        var last: List<JustWatch.Title> = emptyList()
        var at = 0L
    }
    private val sessions = HashMap<String, Session>()

    data class Reply(val text: String, val cards: JSONArray)

    // ---------------- settings ----------------

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun key(c: Context): String = prefs(c).getString("key", "") ?: ""
    fun setKey(c: Context, k: String) = prefs(c).edit().putString("key", k.trim()).apply()
    fun subscriptions(c: Context): List<String> {
        val p = prefs(c)
        if (!p.getBoolean("subsV2", false)) {   // Spotify is paid too
            val old = (p.getString("subs", null) ?: "Netflix,DStv").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            p.edit().putString("subs", (old + if (old.any { it.equals("Spotify", true) }) emptyList() else listOf("Spotify")).joinToString(",")).putBoolean("subsV2", true).apply()
        }
        return (p.getString("subs", null) ?: "Netflix,DStv,Spotify").split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
    }
    /** Spoken replies on the phones (one setting for the whole house). */
    fun voice(c: Context): Boolean = prefs(c).getBoolean("voice", true)
    fun setVoice(c: Context, on: Boolean) = prefs(c).edit().putBoolean("voice", on).apply()
    fun setSubscriptions(c: Context, s: List<String>) = prefs(c).edit().putString("subs", s.joinToString(",")).apply()

    fun status(c: Context): JSONObject = JSONObject().put("ok", true).put("hasKey", key(c).isNotBlank())
        .put("subscriptions", JSONArray(subscriptions(c))).put("voice", voice(c)).put("model", prefs(c).getString("model", MODELS.first()))

    fun reset(session: String) { synchronized(sessions) { sessions.remove(session) } }

    // ---------------- entry ----------------

    fun ask(context: Context, sessionId: String, text: String): Reply {
        val s = synchronized(sessions) {
            val cur = sessions[sessionId]
            if (cur == null || System.currentTimeMillis() - cur.at > 20 * 60_000L) Session().also { sessions[sessionId] = it } else cur
        }
        s.at = System.currentTimeMillis()
        val k = key(context)
        if (k.isBlank()) return rules(context, s, text)
        return try {
            gemini(context, s, k, text)
        } catch (e: QuotaException) {
            Reply("The free AI limit is used up for the moment, so I used the simple version: " + rules(context, s, text).text, JSONArray())
        } catch (e: Exception) {
            rules(context, s, text)
        }
    }

    // ---------------- Gemini ----------------

    private class QuotaException : Exception()

    private fun system(context: Context): String {
        val c = ConfigStore.load(context)
        val profile = c.profiles.getOrNull(c.activeProfile)?.name ?: "family"
        return """
You are Mi TV, the voice assistant on a family TV in Pretoria, South Africa. Today is ${LocalDate.now()}.
Paid subscriptions in this house: ${subscriptions(context).joinToString(", ")}. Free: YouTube, YouTube Kids. Music: Spotify.
The person talking is on the "$profile" profile.
Reply in one or two short spoken sentences. No markdown, no lists, no emojis.
For questions about films or series (the latest one, where to watch it, is it on Netflix) call find_title. It returns South African availability.
Choose the best match yourself: for "latest" pick the most recent release year. Never invent availability.
Then say where it streams. If it is on a service they pay for, ask whether to play it. If not, say which service has it, or that it can only be rented or bought, and where.
When they say yes, play it, put it on, or similar, call play_title for that title.
Songs, artists, albums and playlists: ALWAYS play_music first (Spotify). Only use play_youtube for music if play_music fails or they ask for YouTube or a music video.
Kids videos and clips: play_youtube (kids=true for the Kids profile or children's content).
"Play <film or series>" (or "put on", "watch"): call find_title, then straight away play_title with the best match on a service they have. Do not ask first when they already said play. Never ask which app; you decide from where it streams.
The streaming guide behind find_title does not list DStv. If a film or series isn't on their other services and they have DStv, say you'll look on DStv and call play_title (it searches inside the DStv app). "Play X on DStv" always goes to play_title with service DStv.
If you are not sure whether "play X" is a song or a show, call find_title; if nothing closely matches the name, treat it as a song and use play_music.
When something starts, just say it is playing in a few words. Never say you can only open the app, never list ways to open it.
TV commands: tv_control. Opening an app: open_app. "What's on" or "what am I watching": whats_on.
""".trim()
    }

    private fun tools(): JSONArray {
        fun fn(name: String, desc: String, props: JSONObject?, required: List<String>) = JSONObject().put("name", name).put("description", desc).apply {
            if (props != null) put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)))
        }
        fun str(d: String) = JSONObject().put("type", "string").put("description", d)
        val decls = JSONArray()
            .put(fn("find_title", "Search films and series and where they stream in South Africa.",
                JSONObject().put("query", str("Title or words to search, e.g. superman")).put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("movie", "show", "any")))),
                listOf("query")))
            .put(fn("play_title", "Start a film or series on the TV in the app that has it.",
                JSONObject().put("title", str("Exact title from find_title")).put("year", JSONObject().put("type", "integer")).put("service", str("Optional preferred service, e.g. Netflix")),
                listOf("title")))
            .put(fn("play_youtube", "Play a video on YouTube or YouTube Kids.",
                JSONObject().put("query", str("What to search for")).put("kids", JSONObject().put("type", "boolean")), listOf("query")))
            .put(fn("play_music", "Play a song, artist, album or playlist on Spotify.", JSONObject().put("query", str("Song, artist or playlist")), listOf("query")))
            .put(fn("tv_control", "Control the TV.",
                JSONObject().put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf(
                    "pause", "play", "volume_up", "volume_down", "mute", "home", "back", "next", "previous", "sleep_timer", "cancel_sleep", "screensaver", "next_lofi_song"))))
                    .put("minutes", JSONObject().put("type", "integer")), listOf("action")))
            .put(fn("open_app", "Open an app on the TV.", JSONObject().put("name", str("App name")), listOf("name")))
            .put(fn("whats_on", "Which app is on the TV screen right now.", null, emptyList()))
        return JSONArray().put(JSONObject().put("functionDeclarations", decls))
    }

    private fun gemini(context: Context, s: Session, key: String, text: String): Reply {
        s.contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
        val cards = JSONArray()
        repeat(6) {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system(context)))))
                .put("contents", s.contents)
                .put("tools", tools())
                .put("generationConfig", JSONObject().put("temperature", 0.4).put("maxOutputTokens", 2048))
            val resp = call(context, key, body)
            val content = resp.getJSONArray("candidates").getJSONObject(0).optJSONObject("content")
                ?: return Reply("Sorry, I didn't catch that.", cards)
            if (!content.has("role")) content.put("role", "model")
            s.contents.put(content)                       // keep it exactly as sent (thought signatures included)
            val parts = content.optJSONArray("parts") ?: JSONArray()
            val calls = (0 until parts.length()).mapNotNull { parts.getJSONObject(it).optJSONObject("functionCall") }
            if (calls.isEmpty()) {
                val reply = (0 until parts.length()).mapNotNull { parts.getJSONObject(it).optString("text").takeIf { t -> t.isNotBlank() } }
                    .joinToString(" ").trim()
                trim(s)
                return Reply(reply.ifBlank { "Done." }, cards)
            }
            val results = JSONArray()
            for (fc in calls) {
                val name = fc.optString("name")
                val out = try { runTool(context, s, name, fc.optJSONObject("args") ?: JSONObject(), cards) }
                          catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "failed") }
                results.put(JSONObject().put("functionResponse", JSONObject().put("name", name).put("response", out)))
            }
            s.contents.put(JSONObject().put("role", "user").put("parts", results))
        }
        trim(s)
        return Reply("Sorry, I got stuck on that one.", cards)
    }

    private fun call(context: Context, key: String, body: JSONObject): JSONObject {
        val saved = prefs(context).getString("model", null)
        val order = (listOfNotNull(saved) + MODELS).distinct()
        var last: Exception = Exception("no model")
        for (m in order) {
            val c = (URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=$key").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 8000; readTimeout = 30000
                setRequestProperty("Content-Type", "application/json")
            }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (code in 200..299) { if (saved != m) prefs(context).edit().putString("model", m).apply(); return JSONObject(txt) }
            if (code == 429) throw QuotaException()
            last = Exception("Gemini $code: ${txt.take(200)}")
            if (code != 404) throw last
        }
        throw last
    }

    /** Keep history short without splitting a call from its answer. */
    private fun trim(s: Session) {
        while (s.contents.length() > 24) s.contents.remove(0)
        while (s.contents.length() > 0) {
            val first = s.contents.getJSONObject(0)
            val p0 = first.optJSONArray("parts")?.optJSONObject(0)
            if (first.optString("role") == "user" && p0 != null && p0.has("text")) break
            s.contents.remove(0)
        }
    }

    // ---------------- tools ----------------

    private fun runTool(context: Context, s: Session, name: String, a: JSONObject, cards: JSONArray): JSONObject = when (name) {
        "find_title" -> {
            val kind = a.optString("kind", "any")
            val list = JustWatch.search(a.optString("query")).filter { kind == "any" || kind.isBlank() || it.kind == kind }.take(6)
            s.last = list
            list.take(3).forEach { cards.put(card(context, it)) }
            JSONObject().put("results", JSONArray().apply { list.forEach { put(describe(context, it)) } })
                .put("dstv_not_listed_but_available", dstvApp(context) != null)
        }
        "play_title" -> playTitle(context, s, a.optString("title"), a.optInt("year", 0), a.optString("service"))
        "play_youtube" -> {
            val apps = Apps.installed(context)
            val app = Voice.findApp(apps, if (a.optBoolean("kids")) "youtube kids" else "youtube") ?: Voice.findApp(apps, "youtube")
            if (app == null) JSONObject().put("ok", false).put("error", "YouTube isn't installed")
            else JSONObject().put("ok", true).put("message", Voice.play(context, app, a.optString("query")))
        }
        "play_music" -> {
            val app = Voice.findApp(Apps.installed(context), "spotify")
            if (app == null) {
                val yt = Voice.findApp(Apps.installed(context), "youtube")
                if (yt == null) JSONObject().put("ok", false).put("error", "Neither Spotify nor YouTube is installed")
                else JSONObject().put("ok", true).put("message", Voice.play(context, yt, a.optString("query"))).put("note", "Spotify isn't installed, played on YouTube")
            } else JSONObject().put("ok", true).put("message", Autoplay.spotify(context, app, a.optString("query")))
        }
        "tv_control" -> JSONObject().put("ok", control(context, a.optString("action"), a.optInt("minutes", 30)))
        "open_app" -> {
            val app = Voice.findApp(Apps.installed(context), a.optString("name"))
            if (app == null) JSONObject().put("ok", false).put("error", "No app called ${a.optString("name")}")
            else JSONObject().put("ok", Voice.start(context, launchIntent(context, app.packageName))).put("opened", app.label)
        }
        "whats_on" -> {
            val pkg = Hub.nowPlayingPackage(context)
            JSONObject().put("app", Apps.installed(context).firstOrNull { it.packageName == pkg }?.label ?: if (pkg == context.packageName) "Mi TV home screen" else "unknown")
        }
        else -> JSONObject().put("ok", false).put("error", "unknown tool")
    }

    private fun control(context: Context, action: String, minutes: Int): Boolean = when (action) {
        "pause" -> Hub.pressKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
        "play" -> Hub.pressKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
        "volume_up" -> Hub.pressKey(context, KeyEvent.KEYCODE_VOLUME_UP, 3)
        "volume_down" -> Hub.pressKey(context, KeyEvent.KEYCODE_VOLUME_DOWN, 3)
        "mute" -> Hub.pressKey(context, KeyEvent.KEYCODE_VOLUME_MUTE)
        "home" -> Hub.pressKey(context, KeyEvent.KEYCODE_HOME)
        "back" -> Hub.pressKey(context, KeyEvent.KEYCODE_BACK)
        "next" -> Hub.pressKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
        "previous" -> Hub.pressKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        "sleep_timer" -> { val m = minutes.coerceIn(1, 240); Sleep.schedule(context, m); ConfigStore.update(context) { it.copy(sleepAt = System.currentTimeMillis() + m * 60_000L) }; true }
        "cancel_sleep" -> { Sleep.cancel(context); ConfigStore.update(context) { it.copy(sleepAt = 0L) }; true }
        "screensaver" -> Hub.startScreensaver(context)
        "next_lofi_song" -> Hub.skipAmbient(context)
        else -> false
    }

    // ---------------- availability + playing ----------------

    private fun norm(s: String) = s.lowercase().replace("+", "plus").replace(Regex("[^a-z0-9]"), "")

    private fun subscribed(context: Context, o: JustWatch.Offer): Boolean {
        if (o.type == "FREE" || o.type == "ADS") return true
        val svc = norm(o.service)
        return subscriptions(context).any { sub -> val n = norm(sub); n.isNotEmpty() && (svc.contains(n) || n.contains(svc)) }
    }

    /** The installed TV app for a streaming service name, e.g. "DStv Stream" -> DStv app. */
    private fun serviceApp(context: Context, service: String): TvApp? {
        val apps = Apps.installed(context)
        val n = norm(service)
        val keys = listOf(n, norm(service.split(" ").first()),
            when { n.contains("prime") -> "primevideo"; n.contains("disney") -> "disney"; n.contains("dstv") -> "dstv"; else -> n })
        return apps.firstOrNull { a -> keys.any { k -> k.length >= 3 && norm(a.label).contains(k) } }
    }

    private fun describe(context: Context, t: JustWatch.Title): JSONObject {
        val subbed = t.streaming.filter { subscribed(context, it) }.map { it.service }.distinct()
        val mine = subbed.filter { serviceApp(context, it) != null }
        val noApp = subbed.filter { it !in mine }
        val others = t.streaming.map { it.service }.distinct().filter { it !in subbed }
        return JSONObject().put("title", t.title).put("year", t.year).put("type", t.kind)
            .put("on_your_services", JSONArray(mine))
            .put("subscribed_but_app_not_installed", JSONArray(noApp))
            .put("streaming_elsewhere", JSONArray(others))
            .put("rent_or_buy", JSONArray(t.rentBuy.map { it.service }))
            .put("about", t.description.take(200))
    }

    private fun card(context: Context, t: JustWatch.Title): JSONObject {
        val d = describe(context, t)
        return JSONObject().put("title", t.title).put("year", t.year).put("poster", t.poster).put("type", t.kind)
            .put("on", d.getJSONArray("on_your_services")).put("elsewhere", d.getJSONArray("streaming_elsewhere"))
    }

    /** DStv isn't in the streaming guide, so DStv is searched inside its own app. */
    private fun dstvApp(context: Context): TvApp? =
        if (subscriptions(context).any { norm(it).contains("dstv") }) serviceApp(context, "DStv") else null

    private fun playOnDstv(context: Context, app: TvApp, title: String, poster: String = "", about: String = ""): JSONObject {
        if (!Voice.start(context, launchIntent(context, app.packageName))) return JSONObject().put("ok", false).put("error", "Couldn't open DStv")
        Autoplay.searchAndPlay(context, app.packageName, title)
        Watching.record(context, title, app.packageName, "", Show(title, app.packageName, app.label, "", poster = poster, summary = about))
        return JSONObject().put("ok", true).put("title", title).put("playing_on", app.label).put("note", "searching DStv for it and pressing play")
    }

    private fun playTitle(context: Context, s: Session, title: String, year: Int, prefer: String): JSONObject {
        val dstv = dstvApp(context)
        val wantsDstv = norm(prefer).contains("dstv")
        if (wantsDstv && dstv != null) return playOnDstv(context, dstv, title)
        val pool = s.last.ifEmpty { JustWatch.search(title) }
        val t = pool.firstOrNull { it.title.equals(title, true) && (year == 0 || it.year == year) }
            ?: pool.firstOrNull { it.title.contains(title, true) || title.contains(it.title, true) }
            ?: JustWatch.search(title).firstOrNull()
            ?: return if (dstv != null) playOnDstv(context, dstv, title) else JSONObject().put("ok", false).put("error", "Couldn't find $title")
        val options = (t.streaming.filter { subscribed(context, it) } + t.streaming.filterNot { subscribed(context, it) })
            .mapNotNull { o -> serviceApp(context, o.service)?.let { o to it } }.distinctBy { it.second.packageName }
        val pick = options.firstOrNull { prefer.isNotBlank() && norm(it.first.service).contains(norm(prefer)) } ?: options.firstOrNull()
        if (pick == null) {
            // not on anything the guide knows: DStv may well have it (HBO, M-Net, local shows)
            if (dstv != null) return playOnDstv(context, dstv, t.title, t.poster, t.description)
            return JSONObject().put("ok", false).put("error",
                    t.streaming.firstOrNull { subscribed(context, it) }?.let { "${t.title} is on ${it.service}, but that app isn't installed on the TV" }
                        ?: "${t.title} isn't on your services")
                .put("streaming_elsewhere", JSONArray(t.streaming.map { it.service })).put("rent_or_buy", JSONArray(t.rentBuy.map { it.service }))
        }
        val (offer, app) = pick
        val ok = launch(context, offer, app, t.title)
        if (ok) Watching.record(context, t.title, app.packageName, offer.url,
            Show(t.title, app.packageName, app.label, offer.url, poster = t.poster, summary = t.description, meta = listOf(t.year.takeIf { it > 0 }?.toString() ?: "", app.label).filter { it.isNotBlank() }.joinToString("  ·  ")))
        return JSONObject().put("ok", ok).put("title", t.title).put("playing_on", app.label)
    }

    /** Open the exact title in its app, then keep at it (profile screen, Play button) until it actually plays. */
    fun launch(context: Context, offer: JustWatch.Offer, app: TvApp, title: String = ""): Boolean {
        val netflixId = Regex("netflix\\.com/(?:[a-z-]+/)?title/(\\d+)").find(offer.url)?.groupValues?.get(1)
        val links = buildList {
            if (netflixId != null) {
                add(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.netflix.com/watch/$netflixId")).setPackage(app.packageName).putExtra("source", "30"))
                add(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.netflix.com/title/$netflixId")).setPackage(app.packageName).putExtra("source", "30"))
            }
            if (offer.url.isNotBlank()) add(Intent(Intent.ACTION_VIEW, Uri.parse(offer.url)).setPackage(app.packageName))
        }
        for (link in links) {
            if (Voice.start(context, Intent(link))) {
                Autoplay.follow(context, app.packageName, title, { Voice.start(context, Intent(link)) })
                return true
            }
        }
        // no link the app understands: open it and use its own search
        if (!Voice.start(context, launchIntent(context, app.packageName))) return false
        Autoplay.searchAndPlay(context, app.packageName, title)
        return true
    }

    /** "Play Stranger Things on Netflix" from the simple voice path: find it, then play it like the agent does. */
    fun playByName(context: Context, query: String, preferPkg: String): String? {
        val list = JustWatch.search(query)
        if (list.isEmpty()) return null
        val s = Session().also { it.last = list }
        val pref = Apps.installed(context).firstOrNull { it.packageName == preferPkg }?.label ?: ""
        val r = playTitle(context, s, list.first().title, list.first().year, pref)
        return if (r.optBoolean("ok")) "Playing ${r.optString("title")} on ${r.optString("playing_on")}" else null
    }

    private fun launchIntent(context: Context, pkg: String): Intent? =
        context.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: context.packageManager.getLaunchIntentForPackage(pkg)

    // ---------------- no key: rules ----------------

    private fun rules(context: Context, s: Session, raw: String): Reply {
        val t = raw.lowercase().trim().trimEnd('?', '.', '!')
        val cards = JSONArray()
        val yes = Regex("^(yes|yeah|yep|sure|ok|okay|please|play it|play that|put it on|go|do it|start it)( please)?$")
        if (yes.matches(t) && s.last.isNotEmpty()) {
            val first = s.last.first()
            val r = playTitle(context, s, first.title, first.year, "")
            return Reply(if (r.optBoolean("ok")) "Playing ${first.title} on ${r.optString("playing_on")}." else r.optString("error", "${first.title} isn't on your services, sorry.") + ".", cards)
        }
        val find = Regex("^(?:what'?s|what is|whats)? ?(?:the )?(latest|newest|new|last) (.+?)(?: movie| film| series| show)?$").find(t)
            ?: Regex("^(?:where can i watch|where is|is|find|search for|look for|can i watch) (.+?)(?: on (?:netflix|dstv|showmax))?(?: streaming| available)?$").find(t)
        if (find != null) {
            val latest = find.groupValues.size > 2 && find.groupValues[1] in setOf("latest", "newest", "new", "last")
            val q = if (latest) find.groupValues[2] else find.groupValues[1]
            val wantMovie = t.contains("movie") || t.contains("film")
            val wantShow = t.contains("series") || t.contains("show")
            var list = JustWatch.search(q).filter { (!wantMovie || it.kind == "movie") && (!wantShow || it.kind == "show") }
            if (list.isEmpty()) return Reply("I couldn't find anything called $q.", cards)
            if (latest) list = list.sortedByDescending { it.year }
            s.last = list
            val best = list.first()
            list.take(3).forEach { cards.put(card(context, it)) }
            val d = describe(context, best)
            val mine = d.getJSONArray("on_your_services"); val noApp = d.getJSONArray("subscribed_but_app_not_installed"); val elsewhere = d.getJSONArray("streaming_elsewhere"); val rent = d.getJSONArray("rent_or_buy")
            val name = "${best.title}${if (best.year > 0) " (${best.year})" else ""}"
            val text = when {
                mine.length() > 0 -> "I found $name. It's on ${mine.getString(0)}. Want me to play it?"
                noApp.length() > 0 -> "$name is on ${noApp.getString(0)}, but the ${noApp.getString(0)} app isn't installed on the TV."
                dstvApp(context) != null && mine.length() == 0 -> "$name isn't on your other services, but DStv might have it. Want me to look on DStv?"
                elsewhere.length() > 0 -> "$name is streaming on ${elsewhere.getString(0)}, which you don't have."
                rent.length() > 0 -> "$name isn't on ${subscriptions(context).filterNot { Watching.isMusic(it) }.joinToString(" or ")} yet. You can rent or buy it on ${rent.getString(0)}."
                else -> "I found $name, but it isn't available to stream in South Africa yet."
            }
            return Reply(text, cards)
        }
        Regex("^(?:play|put on|watch|find) (.+?) on (?:dstv|d stv|dstv stream)$").find(t)?.let { m ->
            val d = dstvApp(context) ?: return Reply(if (serviceApp(context, "DStv") == null) "The DStv app isn't installed on the TV." else "DStv isn't ticked as an app you pay for. Tick it in Settings, AI agent.", cards)
            playOnDstv(context, d, m.groupValues[1].replaceFirstChar { it.uppercase() }); return Reply("Looking for ${m.groupValues[1]} on DStv.", cards)
        }
        // "play staying alive" / "play the big bang theory": a show if the name matches one, otherwise a song
        Regex("^(?:play|put on|watch|listen to) (.+?)(?: please)?$").find(t)?.let { m ->
            val q = m.groupValues[1]
            val named = Regex(" (on|in) (netflix|dstv|youtube|youtube kids|spotify|showmax|prime video|disney.*)$").containsMatchIn(q)
            if (!named) {
                val k = { x: String -> x.lowercase().removePrefix("the ").replace(Regex("[^a-z0-9]"), "") }
                val show = JustWatch.search(q).firstOrNull { k(it.title) == k(q) || (k(q).length > 5 && k(it.title).startsWith(k(q))) }
                // a film called "Staying Alive" exists too: only treat it as a show when you can actually watch it here,
                // or you clearly asked to watch something
                val watchable = show != null && show.streaming.any { o -> subscribed(context, o) && serviceApp(context, o.service) != null }
                val wantsVideo = Regex("^(watch|put on)\\b|\\b(movie|film|series|show|episode|season)\\b").containsMatchIn(t)
                if (show != null && !t.startsWith("listen") && (watchable || wantsVideo || Voice.findApp(Apps.installed(context), "spotify") == null)) {
                    s.last = listOf(show); cards.put(card(context, show))
                    val r = playTitle(context, s, show.title, show.year, "")
                    if (r.optBoolean("ok")) return Reply("Playing ${show.title} on ${r.optString("playing_on")}.", cards)
                    return Reply(r.optString("error", "${show.title} isn't on your services.") + ".", cards)
                }
                val apps = Apps.installed(context)
                Voice.findApp(apps, "spotify")?.let { return Reply(Autoplay.spotify(context, it, q) + ".", cards) }
                Voice.findApp(apps, "youtube")?.let { return Reply(Voice.play(context, it, q) + ".", cards) }
            }
        }
        return Reply(Voice.handle(context, raw), cards)
    }
}
