package com.hashira.launcher.weather

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.ImageBitmap
import com.hashira.launcher.R

enum class WeatherKind { SUN, PARTLY, CLOUD, FOG, RAIN, SNOW, THUNDER }

/** Result of looking up the icon for a condition in the pack (bmp = null: none, the drawn one is used). */
class WeatherIconHit(val bmp: ImageBitmap?)

/** Open-Meteo WMO code -> icon type. */
fun weatherKind(code: Int): WeatherKind = when (code) {
    0, 1 -> WeatherKind.SUN
    2 -> WeatherKind.PARTLY
    3 -> WeatherKind.CLOUD
    45, 48 -> WeatherKind.FOG
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> WeatherKind.RAIN
    71, 73, 75, 77, 85, 86 -> WeatherKind.SNOW
    95, 96, 99 -> WeatherKind.THUNDER
    else -> WeatherKind.CLOUD
}

@StringRes
fun weatherDesc(code: Int): Int = when (code) {
    0 -> R.string.wx_clear
    1 -> R.string.wx_mostly_clear
    2 -> R.string.wx_partly_cloudy
    3 -> R.string.wx_cloudy
    45, 48 -> R.string.wx_fog
    51, 53, 55 -> R.string.wx_drizzle
    56, 57 -> R.string.wx_freezing_drizzle
    61, 63, 65 -> R.string.wx_rain
    66, 67 -> R.string.wx_freezing_rain
    71, 73, 75, 77 -> R.string.wx_snow
    80, 81, 82 -> R.string.wx_showers
    85, 86 -> R.string.wx_snow_showers
    95, 96, 99 -> R.string.wx_thunder
    else -> R.string.wx_unknown
}

@StringRes
fun weatherKindLabel(kind: WeatherKind): Int = when (kind) {
    WeatherKind.SUN -> R.string.wx_clear
    WeatherKind.PARTLY -> R.string.wx_partly_cloudy
    WeatherKind.CLOUD -> R.string.wx_cloudy
    WeatherKind.FOG -> R.string.wx_fog
    WeatherKind.RAIN -> R.string.wx_rain
    WeatherKind.SNOW -> R.string.wx_snow
    WeatherKind.THUNDER -> R.string.wx_thunder
}

private val WEATHER_WORDS = listOf("weather", "clima", "wetter", "meteo", "wx")

/**
 * There is no standard for weather icons in icon packs, so lookup is by name:
 * only drawables whose name is about weather ("weather_rain", "ic_wx_snow"…) are considered and
 * then one is chosen by keyword. If there is no match, returns null.
 */
fun detectWeatherIcon(kind: WeatherKind, names: List<String>): String? {
    val candidates = names.filter { n -> WEATHER_WORDS.any { n.contains(it, ignoreCase = true) } }
    if (candidates.isEmpty()) return null

    fun pick(vararg any: String, none: List<String> = emptyList()): String? = candidates.firstOrNull { n ->
        val l = n.lowercase()
        any.any { l.contains(it) } && none.none { l.contains(it) }
    }

    return when (kind) {
        WeatherKind.SUN -> pick("clear", "sunny", "sun",
            none = listOf("cloud", "rain", "snow", "storm", "thunder", "fog", "partly", "set", "rise", "night"))
        WeatherKind.PARTLY -> pick("partly", "few_clouds", "scattered", "sun_cloud", "cloud_sun", "mostly_cloudy", "mostly_sunny")
        WeatherKind.CLOUD -> pick("cloudy", "cloud", "overcast",
            none = listOf("sun", "partly", "rain", "snow", "storm", "thunder", "fog", "night"))
        WeatherKind.FOG -> pick("fog", "mist", "haze", "smoke")
        WeatherKind.RAIN -> pick("rain", "shower", "drizzle", none = listOf("snow", "thunder", "storm"))
        WeatherKind.SNOW -> pick("snow", "flurr", "sleet", "hail")
        WeatherKind.THUNDER -> pick("thunder", "storm", "lightning", "bolt")
    }
}
