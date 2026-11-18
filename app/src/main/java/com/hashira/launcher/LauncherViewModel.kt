package com.hashira.launcher

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.UserHandle
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hashira.launcher.data.AppEntry
import com.hashira.launcher.data.AppRepository
import com.hashira.launcher.data.LauncherSettings
import com.hashira.launcher.data.ProfileInfo
import com.hashira.launcher.data.ProfileKind
import com.hashira.launcher.data.SettingsRepository
import com.hashira.launcher.data.UsageRepository
import com.hashira.launcher.data.WallpaperMode
import com.hashira.launcher.data.WeatherSource
import com.hashira.launcher.icons.IconLoader
import com.hashira.launcher.icons.IconPackInfo
import com.hashira.launcher.icons.IconPackManager
import com.hashira.launcher.weather.DeviceLocation
import com.hashira.launcher.weather.GeoResult
import com.hashira.launcher.weather.WeatherData
import com.hashira.launcher.weather.WeatherIconHit
import com.hashira.launcher.weather.WeatherKind
import com.hashira.launcher.weather.WeatherRepository
import com.hashira.launcher.weather.WeatherScheduler
import com.hashira.launcher.weather.detectWeatherIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Screen { HOME, SETTINGS, ICON_PACKS, FAVORITES, HIDDEN, WEATHER_ICONS }
enum class WeatherStatus { PICK_CITY, NO_PERMISSION, NO_LOCATION, FAILED }
enum class GeoStatus { NO_RESULTS, FAILED }

/** Short message to show as a Toast; the text is resolved by the Activity (with the active language). */
data class UiMessage(@StringRes val res: Int, val arg: String? = null)

class LauncherViewModel(app: Application) : AndroidViewModel(app) {
    val settingsRepo = SettingsRepository(app)
    val appRepo = AppRepository(app)
    val packManager = IconPackManager(app)
    val iconLoader = IconLoader(app, appRepo, packManager)
    private val usageRepo = UsageRepository(app)
    val usage: StateFlow<Map<String, Int>> = usageRepo.counts
    val weatherRepo = WeatherRepository(app)
    val deviceLocation = DeviceLocation(app)
    val lockAdminComponent = ComponentName(app, LockAdminReceiver::class.java)
    private val dpm = app.getSystemService(DevicePolicyManager::class.java)

    val settings: StateFlow<LauncherSettings> = settingsRepo.state

    // Raw system list; "apps" applies custom names and reorders if needed.
    private val rawApps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = combine(
        rawApps,
        settings.map { it.labelOverrides }.distinctUntilChanged()
    ) { list, overrides ->
        if (overrides.isEmpty()) {
            list
        } else {
            val collator = Collator.getInstance()
            list.map { e -> overrides[e.key]?.let { e.copy(label = it) } ?: e }
                .sortedWith { a, b -> collator.compare(a.label, b.label) }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _profiles = MutableStateFlow<List<ProfileInfo>>(emptyList())
    val profiles: StateFlow<List<ProfileInfo>> = _profiles.asStateFlow()

    private val _packs = MutableStateFlow<List<IconPackInfo>>(emptyList())
    val packs: StateFlow<List<IconPackInfo>> = _packs.asStateFlow()

    private val _packError = MutableStateFlow<String?>(null)
    val packError: StateFlow<String?> = _packError.asStateFlow()

    private val _wallpaper = MutableStateFlow<ImageBitmap?>(null)
    val wallpaper: StateFlow<ImageBitmap?> = _wallpaper.asStateFlow()

    private val _weather = MutableStateFlow(weatherRepo.cached())
    val weather: StateFlow<WeatherData?> = _weather.asStateFlow()

    private val _weatherStatus = MutableStateFlow<WeatherStatus?>(null)
    val weatherStatus: StateFlow<WeatherStatus?> = _weatherStatus.asStateFlow()

    private val _weatherDetail = MutableStateFlow<String?>(null)
    val weatherDetail: StateFlow<String?> = _weatherDetail.asStateFlow()

    private val _geoResults = MutableStateFlow<List<GeoResult>>(emptyList())
    val geoResults: StateFlow<List<GeoResult>> = _geoResults.asStateFlow()

    private val _geoStatus = MutableStateFlow<GeoStatus?>(null)
    val geoStatus: StateFlow<GeoStatus?> = _geoStatus.asStateFlow()

    private val _geoBusy = MutableStateFlow(false)
    val geoBusy: StateFlow<Boolean> = _geoBusy.asStateFlow()

    private val _screen = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _homeTick = MutableStateFlow(0)
    val homeTick: StateFlow<Int> = _homeTick.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private var preloadJob: Job? = null
    private val weatherIcons = HashMap<String, WeatherIconHit>()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh(listOf(packageName))
        override fun onPackageAdded(packageName: String, user: UserHandle) = refresh(listOf(packageName))
        override fun onPackageChanged(packageName: String, user: UserHandle) = refresh(listOf(packageName))
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            refresh(packageNames.toList())
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            refresh(packageNames.toList())
    }

    // Lock / unlock Private Space or the work profile
    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    init {
        refresh()
        loadWallpaper()
        appRepo.register(callback)
        app.registerReceiver(
            profileReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            },
            Context.RECEIVER_NOT_EXPORTED
        )
        applyWeatherSchedule()
        refreshWeather()
        // If the pack, the overrides or the size change, the icon cache is warmed again.
        viewModelScope.launch {
            settings.map { Triple(it.iconPack, it.iconSizeDp, it.iconOverrides) }
                .distinctUntilChanged()
                .drop(1)
                .collect { preloadIcons(rawApps.value) }
        }
    }

    override fun onCleared() {
        appRepo.unregister(callback)
        runCatching { getApplication<Application>().unregisterReceiver(profileReceiver) }
    }

    // ---- navigation ----
    fun goHome() { _screen.value = Screen.HOME; _homeTick.value++ }
    fun openSettings() { _screen.value = Screen.SETTINGS }
    fun openFavorites() { _screen.value = Screen.FAVORITES }
    fun openHidden() { _screen.value = Screen.HIDDEN }
    fun openWeatherIcons() { _screen.value = Screen.WEATHER_ICONS }
    fun openIconPacks() { refreshPacks(); _screen.value = Screen.ICON_PACKS }

    // ---- apps ----
    /**
     * Reloads the app list. Icons are invalidated only for packages that changed (or all of them,
     * if the active icon pack changed), and icon packs are searched again only on package events.
     */
    fun refresh(changed: List<String> = emptyList()) {
        viewModelScope.launch(Dispatchers.Default) {
            val selected = settings.value.iconPack
            if (selected != null && selected in changed) {
                packManager.invalidate()
                iconLoader.clear()
            } else {
                changed.forEach { iconLoader.evictPackage(it) }
            }
            val result = appRepo.load()
            rawApps.value = result.apps
            _profiles.value = result.profiles
            if (changed.isNotEmpty() || _packs.value.isEmpty()) _packs.value = packManager.listInstalled()
            preloadIcons(result.apps)
        }
    }

    /** Warms the pack and icons in the background: favorites first, then the rest. */
    private fun preloadIcons(list: List<AppEntry>) {
        preloadJob?.cancel()
        val s = settings.value
        val px = (s.iconSizeDp * getApplication<Application>().resources.displayMetrics.density).roundToInt()
        val favs = s.favorites.toSet()
        val ordered = list.filter { it.key in favs } + list.filter { it.key !in favs }
        preloadJob = viewModelScope.launch(Dispatchers.Default) {
            s.iconPack?.let { packManager.getResult(it) }
            iconLoader.preload(ordered, s.iconPack, s.iconOverrides, px)
        }
    }

    fun refreshPacks() {
        viewModelScope.launch(Dispatchers.Default) { _packs.value = packManager.listInstalled() }
    }

    fun update(block: (LauncherSettings) -> LauncherSettings) = settingsRepo.update(block)

    fun launch(entry: AppEntry) {
        if (appRepo.launch(entry)) {
            // Private Space leaves no trace, not even in the local counter
            if (entry.profile != ProfileKind.PRIVATE) usageRepo.record(entry.key)
        } else {
            refresh()
            toast(R.string.toast_open_failed, entry.label)
        }
    }

    fun openAppInfo(entry: AppEntry) {
        if (!appRepo.openAppInfo(entry)) toast(R.string.toast_info_failed)
    }

    fun startShortcut(info: ShortcutInfo) {
        if (!appRepo.startShortcut(info)) toast(R.string.toast_shortcut_failed)
    }

    fun renameApp(entry: AppEntry, name: String?) = update { s ->
        val clean = name?.trim()?.replace(Regex("\\s+"), " ")
        val m = s.labelOverrides.toMutableMap()
        if (clean.isNullOrEmpty() || clean == entry.originalLabel) m.remove(entry.key) else m[entry.key] = clean
        s.copy(labelOverrides = m)
    }

    /** spec: "app:<other app key>", "sc:<shortcut id of this app>" or null to remove the action. */
    fun setSwipeAction(entry: AppEntry, spec: String?) = update { s ->
        val m = s.swipeActions.toMutableMap()
        if (spec == null) m.remove(entry.key) else m[entry.key] = spec
        s.copy(swipeActions = m)
    }

    fun runSwipeAction(entry: AppEntry) {
        val spec = settings.value.swipeActions[entry.key] ?: return
        when {
            spec.startsWith("app:") ->
                rawApps.value.firstOrNull { it.key == spec.removePrefix("app:") }?.let { launch(it) }
                    ?: toast(R.string.toast_open_failed, entry.label)
            spec.startsWith("sc:") -> viewModelScope.launch(Dispatchers.IO) {
                val info = appRepo.shortcutById(entry, spec.removePrefix("sc:"))
                if (info == null || !appRepo.startShortcut(info)) toast(R.string.toast_shortcut_failed)
            }
        }
    }

    // ---- double tap: turn off and lock the screen (device admin, force-lock only) ----
    fun isLockAdminActive(): Boolean = dpm.isAdminActive(lockAdminComponent)

    fun removeLockAdmin() {
        runCatching { dpm.removeActiveAdmin(lockAdminComponent) }
    }

    fun lockScreen() {
        if (isLockAdminActive()) runCatching { dpm.lockNow() } else toast(R.string.toast_lock_needs_admin)
    }

    // ---- favorites and hidden ----
    fun toggleFavorite(key: String) = update {
        if (key in it.favorites) it.copy(favorites = it.favorites - key) else it.copy(favorites = it.favorites + key)
    }

    fun setFavorite(key: String, on: Boolean) = update {
        when {
            on && key !in it.favorites -> it.copy(favorites = it.favorites + key)
            !on -> it.copy(favorites = it.favorites - key)
            else -> it
        }
    }

    /** Saves the order resulting from dragging; keys that are no longer favorites are ignored. */
    fun setFavoritesOrder(keys: List<String>) = update { s ->
        val valid = keys.filter { it in s.favorites }
        s.copy(favorites = valid + s.favorites.filter { it !in valid })
    }

    fun resetUsage() = usageRepo.reset()

    fun hide(key: String) = update { it.copy(hidden = it.hidden + key, favorites = it.favorites - key) }
    fun unhide(key: String) = update { it.copy(hidden = it.hidden - key) }
    fun unhideAll() = update { it.copy(hidden = emptySet()) }

    fun setProfileLocked(user: UserHandle, locked: Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            if (!appRepo.setQuiet(user, locked)) toast(R.string.toast_private_failed)
        }
    }

    // ---- icon packs ----
    fun selectIconPack(pkg: String?) {
        viewModelScope.launch(Dispatchers.Default) {
            if (pkg == null) {
                _packError.value = null
                update { it.copy(iconPack = null, iconOverrides = emptyMap(), weatherIconOverrides = emptyMap()) }
                iconLoader.clear()
                clearWeatherIcons()
                return@launch
            }
            packManager.load(pkg)
                .onSuccess {
                    _packError.value = null
                    update { s -> s.copy(iconPack = pkg, iconOverrides = emptyMap(), weatherIconOverrides = emptyMap()) }
                    iconLoader.clear()
                    clearWeatherIcons()
                }
                .onFailure { _packError.value = it.message ?: it.javaClass.simpleName }
        }
    }

    fun setOverride(entry: AppEntry, drawable: String?) = update { s ->
        val m = s.iconOverrides.toMutableMap()
        if (drawable == null) m.remove(entry.key) else m[entry.key] = drawable
        s.copy(iconOverrides = m)
    }

    // ---- weather icons taken from the pack ----
    private fun weatherKey(kind: WeatherKind, sizePx: Int): String {
        val s = settings.value
        return "$kind|$sizePx|${s.iconPack}|${s.weatherUsePackIcons}|${s.weatherIconOverrides[kind.name]}"
    }

    private fun clearWeatherIcons() = synchronized(weatherIcons) { weatherIcons.clear() }

    fun peekWeatherIcon(kind: WeatherKind, sizePx: Int): WeatherIconHit? =
        synchronized(weatherIcons) { weatherIcons[weatherKey(kind, sizePx)] }

    /** Icon for the condition from the pack: chosen by hand > detected by name > none (drawn). */
    suspend fun loadWeatherIcon(kind: WeatherKind, sizePx: Int): WeatherIconHit = withContext(Dispatchers.Default) {
        val key = weatherKey(kind, sizePx)
        val s = settings.value
        val pack = s.iconPack?.let { packManager.get(it) }
        val name = s.weatherIconOverrides[kind.name]
            ?: if (s.weatherUsePackIcons && pack != null) detectWeatherIcon(kind, pack.pickerNames) else null
        val bmp = if (pack != null && name != null) pack.load(name)?.toBitmap(sizePx, sizePx)?.asImageBitmap() else null
        WeatherIconHit(bmp).also { hit -> synchronized(weatherIcons) { weatherIcons[key] = hit } }
    }

    fun setWeatherIcon(kind: WeatherKind, drawable: String?) = update { s ->
        val m = s.weatherIconOverrides.toMutableMap()
        if (drawable == null) m.remove(kind.name) else m[kind.name] = drawable
        s.copy(weatherIconOverrides = m)
    }

    // ---- wallpaper ----
    fun setWallpaper(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
                    val longest = maxOf(info.size.width, info.size.height)
                    decoder.setTargetSampleSize((longest / MAX_WALLPAPER_PX).coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                // Recompressed: the local copy does not keep EXIF metadata.
                File(ctx.filesDir, WALLPAPER_FILE).outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
                }
                _wallpaper.value = bmp.asImageBitmap()
                update { it.copy(wallpaperMode = WallpaperMode.IMAGE) }
            } catch (e: Exception) {
                toast(R.string.toast_image_failed)
            }
        }
    }

    private fun loadWallpaper() {
        viewModelScope.launch(Dispatchers.IO) {
            val f = File(getApplication<Application>().filesDir, WALLPAPER_FILE)
            if (f.exists()) _wallpaper.value = BitmapFactory.decodeFile(f.path)?.asImageBitmap()
        }
    }

    // ---- backup ----
    fun exportSettings(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use {
                    it.write(settingsRepo.exportJson().toByteArray())
                }
            }.isSuccess
            toast(if (ok) R.string.toast_export_ok else R.string.toast_export_failed)
        }
    }

    fun importSettings(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            }.getOrNull()
            if (text != null && settingsRepo.importJson(text)) {
                iconLoader.clear()
                clearWeatherIcons()
                applyWeatherSchedule()
                refreshWeather(force = true)
                toast(R.string.toast_import_ok)
            } else {
                toast(R.string.toast_import_failed)
            }
        }
    }

    // ---- weather ----
    fun applyWeatherSchedule() {
        val s = settings.value
        WeatherScheduler.apply(getApplication<Application>(), s.weather.show, s.weatherIntervalMin)
    }

    /**
     * Only uses the network if the element is enabled. Reuses the stored data while it is newer
     * than the chosen interval (the background job writes to the same file).
     */
    fun refreshWeather(force: Boolean = false) {
        val s = settings.value
        if (!s.weather.show) return
        viewModelScope.launch(Dispatchers.IO) {
            weatherRepo.cached()?.let { saved ->
                if (saved.fetchedAt > (_weather.value?.fetchedAt ?: 0L)) _weather.value = saved
            }
            val lat = s.weatherLat
            val lon = s.weatherLon
            val coords: Pair<Double, Double>? = when (s.weatherSource) {
                WeatherSource.CITY -> if (lat != null && lon != null) lat to lon else null
                WeatherSource.DEVICE -> deviceLocation.coarse()
            }
            if (coords == null) {
                _weatherStatus.value = when (s.weatherSource) {
                    WeatherSource.CITY -> WeatherStatus.PICK_CITY
                    WeatherSource.DEVICE ->
                        if (!deviceLocation.hasPermission()) WeatherStatus.NO_PERMISSION else WeatherStatus.NO_LOCATION
                }
                return@launch
            }
            val cached = _weather.value
            val fresh = cached != null &&
                System.currentTimeMillis() - cached.fetchedAt < s.weatherIntervalMin * 60_000L &&
                abs(cached.lat - coords.first) < 0.05 && abs(cached.lon - coords.second) < 0.05
            if (!force && fresh) return@launch
            try {
                _weather.value = weatherRepo.fetch(coords.first, coords.second)
                _weatherStatus.value = null
                _weatherDetail.value = null
            } catch (e: Exception) {
                _weatherStatus.value = WeatherStatus.FAILED
                _weatherDetail.value = e.message ?: e.javaClass.simpleName
            }
        }
    }

    fun searchCity(query: String, lang: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _geoBusy.value = true
            _geoStatus.value = null
            try {
                val r = weatherRepo.searchCity(query, lang)
                _geoResults.value = r
                if (r.isEmpty()) _geoStatus.value = GeoStatus.NO_RESULTS
            } catch (e: Exception) {
                _geoResults.value = emptyList()
                _geoStatus.value = GeoStatus.FAILED
            }
            _geoBusy.value = false
        }
    }

    fun selectCity(r: GeoResult) {
        update { it.copy(weatherSource = WeatherSource.CITY, weatherCity = r.display, weatherLat = r.lat, weatherLon = r.lon) }
        _geoResults.value = emptyList()
        _geoStatus.value = null
        refreshWeather(force = true)
    }

    private fun toast(@StringRes res: Int, arg: String? = null) {
        _messages.tryEmit(UiMessage(res, arg))
    }

    private companion object {
        const val WALLPAPER_FILE = "wallpaper.jpg"
        const val MAX_WALLPAPER_PX = 2400
    }
}
