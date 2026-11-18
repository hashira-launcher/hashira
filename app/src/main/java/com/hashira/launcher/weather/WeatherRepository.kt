package com.hashira.launcher.weather

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

data class GeoResult(
    val name: String,
    val admin: String?,
    val country: String?,
    val lat: Double,
    val lon: Double
) {
    val display: String get() = listOfNotNull(name, admin, country).joinToString(", ")
}

data class WeatherData(
    val tempC: Double,
    val code: Int,
    val maxC: Double,
    val minC: Double,
    val fetchedAt: Long,
    val lat: Double,
    val lon: Double
)

/**
 * Minimal Open-Meteo client (no key, no account). Only called when refreshing the
 * forecast, and coordinates are rounded to 2 decimals (~1 km) before being sent.
 */
class WeatherRepository(context: Context) {
    private val prefs = context.getSharedPreferences("hashira_weather", Context.MODE_PRIVATE)

    fun cached(): WeatherData? {
        val at = prefs.getLong("fetchedAt", 0L)
        if (at == 0L) return null
        return runCatching {
            WeatherData(
                tempC = prefs.getString("temp", null)!!.toDouble(),
                code = prefs.getInt("code", 0),
                maxC = prefs.getString("max", null)!!.toDouble(),
                minC = prefs.getString("min", null)!!.toDouble(),
                fetchedAt = at,
                lat = prefs.getString("lat", null)!!.toDouble(),
                lon = prefs.getString("lon", null)!!.toDouble()
            )
        }.getOrNull()
    }

    suspend fun searchCity(query: String, lang: String): List<GeoResult> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val body = httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=6&language=$lang&format=json")
        val arr = JSONObject(body).optJSONArray("results") ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GeoResult(
                name = o.getString("name"),
                admin = o.optString("admin1").takeIf { it.isNotBlank() },
                country = o.optString("country").takeIf { it.isNotBlank() },
                lat = o.getDouble("latitude"),
                lon = o.getDouble("longitude")
            )
        }
    }

    suspend fun fetch(lat: Double, lon: Double): WeatherData = withContext(Dispatchers.IO) {
        val la = String.format(Locale.US, "%.2f", lat)
        val lo = String.format(Locale.US, "%.2f", lon)
        val body = httpGet(
            "https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo" +
                "&current=temperature_2m,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
        )
        val json = JSONObject(body)
        val cur = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val data = WeatherData(
            tempC = cur.getDouble("temperature_2m"),
            code = cur.getInt("weather_code"),
            maxC = daily.getJSONArray("temperature_2m_max").getDouble(0),
            minC = daily.getJSONArray("temperature_2m_min").getDouble(0),
            fetchedAt = System.currentTimeMillis(),
            lat = la.toDouble(),
            lon = lo.toDouble()
        )
        prefs.edit {
            putString("temp", data.tempC.toString())
            putInt("code", data.code)
            putString("max", data.maxC.toString())
            putString("min", data.minC.toString())
            putLong("fetchedAt", data.fetchedAt)
            putString("lat", data.lat.toString())
            putString("lon", data.lon.toString())
        }
        data
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.requestMethod = "GET"
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

fun formatTemp(celsius: Double, fahrenheit: Boolean): String =
    "${(if (fahrenheit) celsius * 9 / 5 + 32 else celsius).roundToInt()}°"
