package com.hashira.launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class LabelMode { ICON_ONLY, ICON_AND_NAME }
enum class AlignMode { START, END }
enum class WallpaperMode { DARK, SYSTEM, IMAGE }
enum class VerticalAlign { TOP, CENTER, BOTTOM }
enum class HPos { LEFT, CENTER, RIGHT }
enum class AllAppsTrigger { OFF, TOUCH, HOLD }
enum class WeatherSource { CITY, DEVICE }
enum class HapticLevel { OFF, LIGHT, MEDIUM, STRONG }
enum class AppLanguage(val tag: String) { EN("en"), ES("es"), JA("ja") }

/** An element of the header (clock, date, weather…): visible, size and position. */
@Immutable
data class HeaderItem(val show: Boolean, val sizeSp: Int, val pos: HPos)

@Immutable
data class LauncherSettings(
    val labelMode: LabelMode = LabelMode.ICON_ONLY,
    val alignment: AlignMode = AlignMode.START,          // side of the screen where the icons are
    val verticalAlign: VerticalAlign = VerticalAlign.BOTTOM,
    val wallpaperMode: WallpaperMode = WallpaperMode.DARK,
    val iconSizeDp: Int = 40,
    val spacingDp: Int = 14,
    val edgeMarginDp: Int = 36,
    val avoidGestureZone: Boolean = true,
    val iconTapMarginDp: Int = 56,                       // protected zone next to the icons: tapping there does not open the list
    val uiOpacity: Float = 1f,
    val scrimAlpha: Float = 0f,
    val clock: HeaderItem = HeaderItem(false, 36, HPos.LEFT),
    val weekday: HeaderItem = HeaderItem(false, 16, HPos.LEFT),
    val date: HeaderItem = HeaderItem(true, 16, HPos.LEFT),
    val battery: HeaderItem = HeaderItem(false, 16, HPos.LEFT),
    val alarm: HeaderItem = HeaderItem(false, 16, HPos.LEFT),
    val weather: HeaderItem = HeaderItem(false, 18, HPos.LEFT),
    val weatherSource: WeatherSource = WeatherSource.CITY,
    val weatherCity: String = "",
    val weatherLat: Double? = null,
    val weatherLon: Double? = null,
    val weatherFahrenheit: Boolean = false,
    val weatherIconDp: Int = 52,
    val weatherIntervalMin: Int = 60,
    val weatherUsePackIcons: Boolean = true,
    val weatherIconOverrides: Map<String, String> = emptyMap(), // WeatherKind.name -> pack drawable
    val onlyFavorites: Boolean = true,
    val allAppsTrigger: AllAppsTrigger = AllAppsTrigger.TOUCH,
    val swipeOpensList: Boolean = true,
    val edgeZoneDp: Int = 48,                            // width of the edge strip that opens the list instantly (0 = disabled)
    val scrubReachPct: Int = 100,                        // % of the strip height the finger travels to cover the whole list (less = more sensitive)
    val scrubNatural: Boolean = false,                   // scrubbing the list with the finger: false = like an index (reversed), true = the list follows the finger
    val hapticLevel: HapticLevel = HapticLevel.LIGHT,
    val showSettingsDots: Boolean = true,
    val showPrivateSpace: Boolean = true,
    val showFrequent: Boolean = true,
    val frequentCount: Int = 5,
    val doubleTapLock: Boolean = false,
    val lockHidden: Boolean = false,
    val notificationDots: Boolean = false,
    val language: AppLanguage = AppLanguage.EN,
    val iconPack: String? = null,
    val favorites: List<String> = emptyList(),           // component keys, in order
    val hidden: Set<String> = emptySet(),
    val iconOverrides: Map<String, String> = emptyMap(), // component -> pack drawable of the active pack
    val labelOverrides: Map<String, String> = emptyMap(), // component -> custom name
    val swipeActions: Map<String, String> = emptyMap()   // component -> "app:<key>" or "sc:<shortcut id>"
)

/** Settings in SharedPreferences, exposed as StateFlow. All local. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("hashira_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<LauncherSettings> = _state.asStateFlow()

    @Synchronized
    fun update(block: (LauncherSettings) -> LauncherSettings) {
        val next = block(_state.value)
        if (next == _state.value) return
        _state.value = next
        write(next)
    }

    /** Backup: each value with a type tag (b:, i:, f:, s:). Does not include the wallpaper image. */
    fun exportJson(): String {
        val out = JSONObject()
        for ((key, value) in prefs.all) {
            val tagged = when (value) {
                is Boolean -> "b:$value"
                is Int -> "i:$value"
                is Float -> "f:$value"
                is String -> "s:$value"
                else -> continue
            }
            out.put(key, tagged)
        }
        return JSONObject().put("app", "hashira").put("format", 1).put("settings", out).toString(2)
    }

    /** Applies an exported file. All or nothing: if anything fails nothing is changed. */
    @Synchronized
    fun importJson(text: String): Boolean = runCatching {
        val root = JSONObject(text)
        require(root.optString("app") == "hashira")
        val src = root.getJSONObject("settings")
        prefs.edit {
            for (key in src.keys()) {
                val raw = src.getString(key)
                val body = raw.substring(2)
                when (raw.substring(0, 2)) {
                    "b:" -> putBoolean(key, body.toBooleanStrict())
                    "i:" -> putInt(key, body.toInt())
                    "f:" -> putFloat(key, body.toFloat())
                    "s:" -> putString(key, body)
                }
            }
        }
        _state.value = read()
        true
    }.getOrDefault(false)

    private fun read(): LauncherSettings {
        val d = LauncherSettings()
        return LauncherSettings(
            labelMode = enumOr(prefs.getString("labelMode", null), d.labelMode),
            alignment = enumOr(prefs.getString("alignment", null), d.alignment),
            verticalAlign = enumOr(prefs.getString("verticalAlign", null), d.verticalAlign),
            wallpaperMode = enumOr(prefs.getString("wallpaperMode", null), d.wallpaperMode),
            iconSizeDp = prefs.getInt("iconSizeDp", d.iconSizeDp).coerceIn(32, 72),
            spacingDp = prefs.getInt("spacingDp", d.spacingDp).coerceIn(0, 28),
            edgeMarginDp = prefs.getInt("edgeMarginDp", d.edgeMarginDp).coerceIn(0, 120),
            avoidGestureZone = prefs.getBoolean("avoidGestureZone", d.avoidGestureZone),
            iconTapMarginDp = prefs.getInt("iconTapMarginDp", d.iconTapMarginDp).coerceIn(0, 120),
            uiOpacity = prefs.getFloat("uiOpacity", d.uiOpacity).coerceIn(0.3f, 1f),
            scrimAlpha = prefs.getFloat("scrimAlpha", d.scrimAlpha).coerceIn(0f, 0.9f),
            clock = readItem("clock", d.clock),
            weekday = readItem("weekday", d.weekday),
            date = readItem("date", d.date),
            battery = readItem("battery", d.battery),
            alarm = readItem("alarm", d.alarm),
            weather = readItem("weather", d.weather),
            weatherSource = enumOr(prefs.getString("weatherSource", null), d.weatherSource),
            weatherCity = prefs.getString("weatherCity", "") ?: "",
            weatherLat = prefs.getString("weatherLat", null)?.toDoubleOrNull(),
            weatherLon = prefs.getString("weatherLon", null)?.toDoubleOrNull(),
            weatherFahrenheit = prefs.getBoolean("weatherFahrenheit", d.weatherFahrenheit),
            weatherIconDp = prefs.getInt("weatherIconDp", d.weatherIconDp).coerceIn(32, 96),
            weatherIntervalMin = prefs.getInt("weatherIntervalMin", d.weatherIntervalMin).coerceIn(15, 720),
            weatherUsePackIcons = prefs.getBoolean("weatherUsePackIcons", d.weatherUsePackIcons),
            weatherIconOverrides = decodeMap(prefs.getString("weatherIconOverrides", "") ?: ""),
            onlyFavorites = prefs.getBoolean("homeOnlyFavorites", d.onlyFavorites),
            allAppsTrigger = enumOr(prefs.getString("allAppsTrigger", null), d.allAppsTrigger),
            swipeOpensList = prefs.getBoolean("swipeOpensList", d.swipeOpensList),
            scrubNatural = prefs.getBoolean("scrubNatural", d.scrubNatural),
            edgeZoneDp = prefs.getInt("edgeZoneDp", d.edgeZoneDp).coerceIn(0, 120),
            scrubReachPct = prefs.getInt("scrubReachPct", d.scrubReachPct).coerceIn(40, 100),
            hapticLevel = enumOr(prefs.getString("hapticLevel", null), d.hapticLevel),
            showSettingsDots = prefs.getBoolean("showSettingsDots", d.showSettingsDots),
            showPrivateSpace = prefs.getBoolean("showPrivateSpace", d.showPrivateSpace),
            showFrequent = prefs.getBoolean("showFrequent", d.showFrequent),
            frequentCount = prefs.getInt("frequentCount", d.frequentCount).coerceIn(3, 8),
            doubleTapLock = prefs.getBoolean("doubleTapLock", d.doubleTapLock),
            lockHidden = prefs.getBoolean("lockHidden", d.lockHidden),
            notificationDots = prefs.getBoolean("notificationDots", d.notificationDots),
            language = enumOr(prefs.getString("language", null), d.language),
            iconPack = prefs.getString("iconPack", null),
            favorites = decodeList(prefs.getString("favorites", "") ?: ""),
            hidden = decodeList(prefs.getString("hidden", "") ?: "").toSet(),
            iconOverrides = decodeMap(prefs.getString("overrides", "") ?: ""),
            labelOverrides = decodeMap(prefs.getString("labelOverrides", "") ?: ""),
            swipeActions = decodeMap(prefs.getString("swipeActions", "") ?: "")
        )
    }

    private fun write(s: LauncherSettings) = prefs.edit {
        putString("labelMode", s.labelMode.name)
        putString("alignment", s.alignment.name)
        putString("verticalAlign", s.verticalAlign.name)
        putString("wallpaperMode", s.wallpaperMode.name)
        putInt("iconSizeDp", s.iconSizeDp)
        putInt("spacingDp", s.spacingDp)
        putInt("edgeMarginDp", s.edgeMarginDp)
        putBoolean("avoidGestureZone", s.avoidGestureZone)
        putInt("iconTapMarginDp", s.iconTapMarginDp)
        putFloat("uiOpacity", s.uiOpacity)
        putFloat("scrimAlpha", s.scrimAlpha)
        putItem("clock", s.clock)
        putItem("weekday", s.weekday)
        putItem("date", s.date)
        putItem("battery", s.battery)
        putItem("alarm", s.alarm)
        putItem("weather", s.weather)
        putString("weatherSource", s.weatherSource.name)
        putString("weatherCity", s.weatherCity)
        putString("weatherLat", s.weatherLat?.toString())
        putString("weatherLon", s.weatherLon?.toString())
        putBoolean("weatherFahrenheit", s.weatherFahrenheit)
        putInt("weatherIconDp", s.weatherIconDp)
        putInt("weatherIntervalMin", s.weatherIntervalMin)
        putBoolean("weatherUsePackIcons", s.weatherUsePackIcons)
        putString("weatherIconOverrides", encodeMap(s.weatherIconOverrides))
        putBoolean("homeOnlyFavorites", s.onlyFavorites)
        putString("allAppsTrigger", s.allAppsTrigger.name)
        putBoolean("swipeOpensList", s.swipeOpensList)
        putBoolean("scrubNatural", s.scrubNatural)
        putInt("edgeZoneDp", s.edgeZoneDp)
        putInt("scrubReachPct", s.scrubReachPct)
        putString("hapticLevel", s.hapticLevel.name)
        putBoolean("showSettingsDots", s.showSettingsDots)
        putBoolean("showPrivateSpace", s.showPrivateSpace)
        putBoolean("showFrequent", s.showFrequent)
        putInt("frequentCount", s.frequentCount)
        putBoolean("doubleTapLock", s.doubleTapLock)
        putBoolean("lockHidden", s.lockHidden)
        putBoolean("notificationDots", s.notificationDots)
        putString("language", s.language.name)
        putString("iconPack", s.iconPack)
        putString("favorites", s.favorites.joinToString("\n"))
        putString("hidden", s.hidden.joinToString("\n"))
        putString("overrides", encodeMap(s.iconOverrides))
        putString("labelOverrides", encodeMap(s.labelOverrides))
        putString("swipeActions", encodeMap(s.swipeActions))
    }

    private fun readItem(name: String, d: HeaderItem) = HeaderItem(
        show = prefs.getBoolean("${name}_show", d.show),
        sizeSp = prefs.getInt("${name}_size", d.sizeSp).coerceIn(8, 120),
        pos = enumOr(prefs.getString("${name}_pos", null), d.pos)
    )

    private fun SharedPreferences.Editor.putItem(name: String, i: HeaderItem) {
        putBoolean("${name}_show", i.show)
        putInt("${name}_size", i.sizeSp)
        putString("${name}_pos", i.pos.name)
    }

    private inline fun <reified T : Enum<T>> enumOr(v: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == v } ?: default

    private fun decodeList(raw: String): List<String> = raw.split('\n').filter { it.isNotBlank() }

    private fun encodeMap(m: Map<String, String>): String =
        m.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    private fun decodeMap(raw: String): Map<String, String> =
        raw.split('\n').mapNotNull { line ->
            val i = line.indexOf('\t')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
}
