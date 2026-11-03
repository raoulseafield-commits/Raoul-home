package za.co.dipixel.launcher.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

data class WeatherNow(val tempC: Int, val description: String, val isDay: Boolean, val code: Int)

/** Open-Meteo, no API key, no account. */
object Weather {
    suspend fun fetch(lat: Double, lon: Double): WeatherNow? = withContext(Dispatchers.IO) {
        try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day&timezone=auto"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val cur = JSONObject(body).getJSONObject("current")
            val code = cur.getInt("weather_code")
            WeatherNow(
                tempC = cur.getDouble("temperature_2m").roundToInt(),
                description = describe(code),
                isDay = cur.optInt("is_day", 1) == 1,
                code = code
            )
        } catch (e: Exception) {
            null
        }
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80, 81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm, hail"
        else -> "Weather"
    }
}
