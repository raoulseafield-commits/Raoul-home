package za.co.dipixel.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Talks to the hub inside Mi TV on the box. Pairing is stored for good: only uninstalling removes it. */
class HubClient(context: Context) {
    private val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)
    var host: String? = prefs.getString("host", null)
        set(v) { field = v; prefs.edit().putString("host", v).apply() }
    var token: String? = prefs.getString("token", null)
        set(v) { field = v; prefs.edit().putString("token", v).apply() }

    fun base() = "http://$host:8765"

    suspend fun hello() = request("GET", "/hello", null)
    suspend fun pair(code: String): Boolean {
        val r = request("POST", "/pair", JSONObject().put("code", code)) ?: return false
        val t = r.optString("token")
        if (r.optBoolean("ok") && t.isNotBlank()) { token = t; return true }
        return false
    }
    suspend fun state() = request("GET", "/state", null)
    suspend fun apps() = request("GET", "/apps", null)
    suspend fun key(name: String, long: Boolean = false) = request("POST", "/key", JSONObject().put("key", name).put("long", long))
    suspend fun open(pkg: String) = request("POST", "/open", JSONObject().put("pkg", pkg))
    suspend fun say(t: String) = request("POST", "/say", JSONObject().put("text", t), readTimeout = 20_000)
    suspend fun profile(i: Int) = request("POST", "/profile", JSONObject().put("index", i))
    suspend fun night(on: Boolean) = request("POST", "/night", JSONObject().put("on", on))
    suspend fun sleep(minutes: Int) = request("POST", "/sleep", JSONObject().put("minutes", minutes))
    suspend fun screensaver() = request("POST", "/screensaver", JSONObject())
    suspend fun newArt() = request("POST", "/art/refresh", JSONObject())
    suspend fun skipArt() = request("POST", "/screensaver/skip", JSONObject())
    suspend fun lofi(on: Boolean) = request("POST", "/lofi", JSONObject().put("on", on))
    suspend fun lofiLevel(level: Int) = request("POST", "/lofi", JSONObject().put("level", level))
    suspend fun playShow(profile: Int, index: Int) = request("POST", "/show/play", JSONObject().put("profile", profile).put("index", index), readTimeout = 20_000)
    suspend fun removeShow(profile: Int, index: Int) = request("POST", "/show/remove", JSONObject().put("profile", profile).put("index", index))
    suspend fun addShow(show: JSONObject, play: Boolean) = request("POST", "/show/add", show.put("play", play), readTimeout = 25_000)

    suspend fun agent(session: String, text: String) = request("POST", "/agent", JSONObject().put("session", session).put("text", text), readTimeout = 45_000)
    suspend fun diag() = request("GET", "/diag", null, readTimeout = 8000)
    suspend fun setVoice(on: Boolean) = request("POST", "/agent/voice", JSONObject().put("on", on))
    suspend fun agentStatus() = request("GET", "/agent/status", null)
    suspend fun setAgentKey(key: String) = request("POST", "/agent/key", JSONObject().put("key", key))
    suspend fun setSubscriptions(list: List<String>) = request("POST", "/agent/subs", JSONObject().put("subscriptions", JSONArray(list)))

    fun iconUrl(pkg: String) = base() + "/icon?pkg=" + URLEncoder.encode(pkg, "UTF-8")

    private suspend fun request(method: String, path: String, body: JSONObject?, readTimeout: Int = 6000): JSONObject? =
        withContext(Dispatchers.IO) {
            if (host.isNullOrBlank()) return@withContext null
            try {
                val c = (URL(base() + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 3000
                    this.readTimeout = readTimeout
                    setRequestProperty("x-hub-token", token ?: "")
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        outputStream.use { it.write(body.toString().toByteArray()) }
                    }
                }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
                c.disconnect()
                JSONObject(text).put("_code", code)
            } catch (e: Exception) { null }
        }
}

/**
 * One permanent socket to the TV for remote buttons. Reconnects on its own forever
 * (Wi-Fi drop, TV reboot, IP change), pings every 4 s so a dead link is noticed fast.
 */
class ControlLink(private val hub: HubClient) {
    val connected = MutableStateFlow(false)
    /** The TV no longer knows this phone (someone pressed "unpair all" on the TV). */
    val rejected = MutableStateFlow(false)
    private val queue = LinkedBlockingQueue<String>()
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var lastHeard = 0L

    fun start() {
        if (running) return
        running = true
        Thread { loop() }.apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (e: Exception) {}
    }

    /** Kick the link to retry right now (e.g. after the IP changed). */
    fun poke() { try { socket?.close() } catch (e: Exception) {} }

    fun key(name: String) = queue.offer("k $name")
    fun longKey(name: String) = queue.offer("l $name")
    fun type(text: String) = queue.offer("t " + text.replace("\n", " "))

    private fun loop() {
        while (running) {
            val h = hub.host; val t = hub.token
            if (h.isNullOrBlank() || t.isNullOrBlank()) { Thread.sleep(1000); continue }
            try {
                val s = Socket()
                socket = s
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(h, 8767), 2500)
                s.soTimeout = 15_000
                val out = s.getOutputStream()
                val r = BufferedReader(InputStreamReader(s.getInputStream()))
                out.write("a $t\n".toByteArray()); out.flush()
                val first = r.readLine()
                if (first != "ok") {
                    rejected.value = first == "no"
                    s.close(); Thread.sleep(4000); continue
                }
                rejected.value = false
                queue.clear()                 // never replay presses made while offline
                lastHeard = System.currentTimeMillis()
                connected.value = true
                Thread {
                    try { while (r.readLine() != null) lastHeard = System.currentTimeMillis() } catch (e: Exception) {}
                    try { s.close() } catch (e: Exception) {}
                }.apply { isDaemon = true }.start()
                while (running && !s.isClosed) {
                    val line = queue.poll(4, TimeUnit.SECONDS) ?: "p"
                    out.write((line + "\n").toByteArray()); out.flush()
                    if (System.currentTimeMillis() - lastHeard > 13_000) break
                }
                s.close()
            } catch (e: Exception) {
            }
            connected.value = false
            if (running) Thread.sleep(700)
        }
    }
}

/** Finds the TV on the Wi-Fi by name, so nobody has to type an IP. Also notices if its IP changes. */
class Discovery(private val context: Context, private val onFound: (String) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var resolving = false
    private var active = false
    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onServiceLost(service: NsdServiceInfo) {}
        override fun onServiceFound(service: NsdServiceInfo) {
            if (resolving) return
            resolving = true
            @Suppress("DEPRECATION")
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { resolving = false }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    resolving = false
                    @Suppress("DEPRECATION")
                    val h = info.host?.hostAddress
                    if (h != null && !h.contains(":")) onFound(h)
                }
            })
        }
    }

    fun start() {
        if (active) return
        try {
            lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createMulticastLock("mitv").apply { setReferenceCounted(false); acquire() }
            nsd.discoverServices("_dipixelhub._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            active = true
        } catch (e: Exception) { }
    }

    fun stop() {
        if (!active) return
        active = false
        try { nsd.stopServiceDiscovery(listener) } catch (e: Exception) { }
        try { lock?.release() } catch (e: Exception) { }
    }
}

/** Search for shows (TVmaze) and movies (iTunes). Free, no keys. */
object TitleSearch {
    data class Result(val title: String, val kind: String, val poster: String, val year: String, val summary: String, val network: String, val genres: String) {
        fun toShowJson(pkg: String, label: String): JSONObject = JSONObject()
            .put("title", title).put("pkg", pkg).put("label", label)
            .put("poster", poster).put("summary", summary)
            .put("meta", listOf(year, network, genres).filter { it.isNotBlank() }.joinToString("  ·  "))
    }

    suspend fun search(q: String): List<Result> = withContext(Dispatchers.IO) {
        val enc = URLEncoder.encode(q, "UTF-8")
        val shows = try {
            val arr = JSONArray(get("https://api.tvmaze.com/search/shows?q=$enc"))
            (0 until arr.length()).mapNotNull { i ->
                val s = arr.getJSONObject(i).optJSONObject("show") ?: return@mapNotNull null
                val g = s.optJSONArray("genres")
                Result(
                    title = s.optString("name"), kind = "Show",
                    poster = s.optJSONObject("image")?.optString("original").orEmpty(),
                    year = s.optString("premiered").take(4),
                    summary = s.optString("summary").replace(Regex("<[^>]+>"), "").trim(),
                    network = (s.optJSONObject("webChannel") ?: s.optJSONObject("network"))?.optString("name").orEmpty(),
                    genres = g?.let { (0 until it.length()).joinToString(", ") { j -> it.getString(j) } }.orEmpty()
                )
            }
        } catch (e: Exception) { emptyList() }
        val movies = try {
            val arr = JSONObject(get("https://itunes.apple.com/search?media=movie&entity=movie&limit=15&term=$enc")).getJSONArray("results")
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                Result(
                    title = m.optString("trackName"), kind = "Movie",
                    poster = m.optString("artworkUrl100").replace("100x100bb", "600x900bb"),
                    year = m.optString("releaseDate").take(4),
                    summary = m.optString("longDescription", m.optString("shortDescription")),
                    network = "", genres = m.optString("primaryGenreName")
                )
            }
        } catch (e: Exception) { emptyList() }
        // interleave so both kinds show near the top
        val out = ArrayList<Result>()
        val max = maxOf(shows.size, movies.size)
        for (i in 0 until max) { shows.getOrNull(i)?.let { out += it }; movies.getOrNull(i)?.let { out += it } }
        out.filter { it.title.isNotBlank() }
    }

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 10000; setRequestProperty("User-Agent", "MiRemote/1.0") }
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }
}
