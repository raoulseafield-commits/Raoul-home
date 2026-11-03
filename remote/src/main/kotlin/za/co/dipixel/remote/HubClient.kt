package za.co.dipixel.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Talks to the hub running inside Dipixel Home on the TV box (port 8765, home Wi-Fi only). */
class HubClient(context: Context) {
    private val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)
    var host: String? = prefs.getString("host", null)
        set(v) { field = v; prefs.edit().putString("host", v).apply() }
    var token: String? = prefs.getString("token", null)
        set(v) { field = v; prefs.edit().putString("token", v).apply() }

    private fun base() = "http://$host:8765"

    suspend fun hello(): JSONObject? = request("GET", "/hello", null)
    suspend fun pair(code: String): Boolean {
        val r = request("POST", "/pair", JSONObject().put("code", code)) ?: return false
        val t = r.optString("token")
        if (r.optBoolean("ok") && t.isNotBlank()) { token = t; return true }
        return false
    }
    suspend fun key(name: String) = request("POST", "/key", JSONObject().put("key", name))
    suspend fun text(t: String) = request("POST", "/text", JSONObject().put("text", t))
    suspend fun open(pkg: String) = request("POST", "/open", JSONObject().put("pkg", pkg))
    suspend fun say(t: String) = request("POST", "/say", JSONObject().put("text", t), readTimeout = 20_000)
    suspend fun apps() = request("GET", "/apps", null)

    suspend fun icon(pkg: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val c = (URL(base() + "/icon?pkg=" + URLEncoder.encode(pkg, "UTF-8")).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000; readTimeout = 5000
                setRequestProperty("x-hub-token", token ?: "")
            }
            c.inputStream.use { BitmapFactory.decodeStream(it) }.also { c.disconnect() }
        } catch (e: Exception) { null }
    }

    private suspend fun request(method: String, path: String, body: JSONObject?, readTimeout: Int = 5000): JSONObject? =
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
                val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
                c.disconnect()
                JSONObject(text).put("_code", c.responseCode)
            } catch (e: Exception) { null }
        }
}

/** Finds the TV on the Wi-Fi by its network name, no IP typing needed. */
class Discovery(private val context: Context, private val onFound: (String) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var lock: WifiManager.MulticastLock? = null
    private var resolving = false
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
        try {
            lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createMulticastLock("dipixel").apply { setReferenceCounted(false); acquire() }
            nsd.discoverServices("_dipixelhub._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) { }
    }

    fun stop() {
        try { nsd.stopServiceDiscovery(listener) } catch (e: Exception) { }
        try { lock?.release() } catch (e: Exception) { }
    }
}
