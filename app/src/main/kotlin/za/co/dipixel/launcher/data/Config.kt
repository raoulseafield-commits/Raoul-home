package za.co.dipixel.launcher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One tile in the "continue watching" row. If [url] is blank the show is found by title in its app. */
data class Show(
    val title: String,
    val providerPackage: String,
    val providerLabel: String,
    val url: String = "",
    /** Landscape backdrop for the full screen hero (may be blank). */
    val backdrop: String = "",
    /** Portrait poster (may be blank). */
    val poster: String = "",
    val summary: String = "",
    /** e.g. "2019 · Drama, Thriller" */
    val meta: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("pkg", providerPackage)
        .put("label", providerLabel)
        .put("url", url)
        .put("backdrop", backdrop)
        .put("poster", poster)
        .put("summary", summary)
        .put("meta", meta)

    companion object {
        fun fromJson(o: JSONObject) = Show(
            title = o.optString("title"),
            providerPackage = o.optString("pkg"),
            providerLabel = o.optString("label"),
            url = o.optString("url"),
            backdrop = o.optString("backdrop"),
            poster = o.optString("poster"),
            summary = o.optString("summary"),
            meta = o.optString("meta")
        )
    }
}

data class Profile(val name: String, val shows: List<Show>) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("shows", JSONArray().apply { shows.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Profile {
            val arr = o.optJSONArray("shows") ?: JSONArray()
            return Profile(
                name = o.optString("name", "Profile"),
                shows = (0 until arr.length()).map { Show.fromJson(arr.getJSONObject(it)) }
            )
        }
    }
}

data class Config(
    val profiles: List<Profile> = listOf(
        Profile("Raoul", emptyList()),
        Profile("Wife", emptyList()),
        Profile("Kids", emptyList())
    ),
    val activeProfile: Int = 0,
    /** Ordered favourite app packages for the apps row. Empty = auto (first installed apps). */
    val favouriteApps: List<String> = emptyList(),
    /** Enabled wallpaper ids out of [Wallpapers.ALL]. */
    val wallpapers: List<String> = Wallpapers.ALL,
    /** Seconds between wallpaper changes. */
    val wallpaperIntervalSec: Int = 900,
    val cityName: String = "Pretoria",
    val latitude: Double = -25.7479,
    val longitude: Double = 28.2293,
    val use24h: Boolean = true,
    val showSeconds: Boolean = false,
    val nightMode: Boolean = false,
    /** Epoch millis when the sleep timer fires, 0 = off. */
    val sleepAt: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject()
        .put("profiles", JSONArray().apply { profiles.forEach { put(it.toJson()) } })
        .put("activeProfile", activeProfile)
        .put("favouriteApps", JSONArray(favouriteApps))
        .put("wallpapers", JSONArray(wallpapers))
        .put("wallpaperIntervalSec", wallpaperIntervalSec)
        .put("cityName", cityName)
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("use24h", use24h)
        .put("showSeconds", showSeconds)
        .put("nightMode", nightMode)
        .put("sleepAt", sleepAt)

    companion object {
        fun fromJson(o: JSONObject): Config {
            val d = Config()
            val pArr = o.optJSONArray("profiles")
            val profiles = if (pArr == null || pArr.length() == 0) d.profiles
            else (0 until pArr.length()).map { Profile.fromJson(pArr.getJSONObject(it)) }
            return Config(
                profiles = profiles,
                activeProfile = o.optInt("activeProfile", 0).coerceIn(0, profiles.lastIndex),
                favouriteApps = o.optJSONArray("favouriteApps").toStringList(),
                wallpapers = (o.optJSONArray("wallpapers")?.toStringList() ?: d.wallpapers).filter { it in Wallpapers.ALL }.ifEmpty { d.wallpapers },
                wallpaperIntervalSec = o.optInt("wallpaperIntervalSec", d.wallpaperIntervalSec),
                cityName = o.optString("cityName", d.cityName),
                latitude = o.optDouble("latitude", d.latitude),
                longitude = o.optDouble("longitude", d.longitude),
                use24h = o.optBoolean("use24h", d.use24h),
                showSeconds = o.optBoolean("showSeconds", d.showSeconds),
                nightMode = o.optBoolean("nightMode", d.nightMode),
                sleepAt = o.optLong("sleepAt", 0L)
            )
        }

        private fun JSONArray?.toStringList(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { getString(it) }
    }
}

object Wallpapers {
    val ALL = listOf("dw1", "dw2", "dw3", "dw4", "dw5", "dw6", "dw7")
    val LABELS = mapOf(
        "dw1" to "Matterhorn, pink sky",
        "dw2" to "Torres del Paine, sunrise",
        "dw3" to "Matterhorn, golden lake",
        "dw4" to "Sangre de Cristo, dusk",
        "dw5" to "Mount Rainier, alpenglow",
        "dw6" to "Matterhorn, purple dusk",
        "dw7" to "Torres del Paine, pink clouds"
    )
}

/** Depth wallpaper details made at build time: clock colours, where the mountain cut-out sits, credit. */
data class WallInfo(val id: String, val c1: Int, val c2: Int, val fg: List<Float>, val credit: String)

object WallCatalog {
    @Volatile private var cache: Map<String, WallInfo>? = null
    fun get(context: Context): Map<String, WallInfo> {
        cache?.let { return it }
        val out = HashMap<String, WallInfo>()
        try {
            val arr = JSONArray(context.assets.open("walls.json").bufferedReader().use { it.readText() })
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                fun col(a: JSONArray) = (0xFF shl 24) or (a.getInt(0) shl 16) or (a.getInt(1) shl 8) or a.getInt(2)
                val fg = o.getJSONArray("fg")
                out[o.getString("name")] = WallInfo(o.getString("name"), col(o.getJSONArray("c1")), col(o.getJSONArray("c2")),
                    (0 until 4).map { fg.getDouble(it).toFloat() }, o.optString("credit"))
            }
        } catch (e: Exception) { }
        cache = out
        return out
    }
}

/** Tiny persistence layer: one JSON blob in SharedPreferences. */
object ConfigStore {
    private const val PREFS = "launcher"
    private const val KEY = "config"

    /** Every save lands here, so the TV screen updates the moment a phone changes something. */
    val flow = kotlinx.coroutines.flow.MutableStateFlow<Config?>(null)

    fun load(context: Context): Config {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return try {
            if (raw == null) Config() else Config.fromJson(JSONObject(raw))
        } catch (e: Exception) {
            Config()
        }
    }

    fun save(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, config.toJson().toString()).apply()
        flow.value = config
    }

    @Synchronized
    fun update(context: Context, change: (Config) -> Config): Config {
        val c = change(load(context))
        save(context, c)
        return c
    }
}
