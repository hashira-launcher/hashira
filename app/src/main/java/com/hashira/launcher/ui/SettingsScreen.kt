package com.hashira.launcher.ui

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hashira.launcher.LauncherViewModel
import com.hashira.launcher.R
import com.hashira.launcher.data.AlignMode
import com.hashira.launcher.data.AllAppsTrigger
import com.hashira.launcher.data.AppLanguage
import com.hashira.launcher.data.HapticLevel
import com.hashira.launcher.data.LabelMode
import com.hashira.launcher.data.VerticalAlign
import com.hashira.launcher.data.WallpaperMode
import kotlin.math.roundToInt

/** Version name of the installed app, so the footer always matches hashira.versionName in gradle.properties. */
@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        }.getOrNull().orEmpty()
    }
}

@Composable
fun SettingsScreen(vm: LauncherViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    BackHandler { vm.goHome() }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setWallpaper(uri)
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportSettings(uri)
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importSettings(uri)
    }

    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            TextButton(onClick = { vm.goHome() }) { Text(stringResource(R.string.back_home)) }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)

            Section(R.string.sec_language)
            ChoiceRow(
                listOf(
                    AppLanguage.EN to "English",
                    AppLanguage.ES to "Español",
                    AppLanguage.JA to "日本語"
                ),
                s.language
            ) { l -> vm.update { it.copy(language = l) } }

            Section(R.string.sec_favorites)
            SwitchRow(stringResource(R.string.fav_only), s.onlyFavorites) { v -> vm.update { it.copy(onlyFavorites = v) } }
            TextButton(onClick = { vm.openFavorites() }) { Text(stringResource(R.string.fav_pick, s.favorites.size)) }
            if (s.onlyFavorites && s.allAppsTrigger == AllAppsTrigger.OFF && !s.swipeOpensList) {
                Text(
                    stringResource(R.string.warn_no_access),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            SwitchRow(stringResource(R.string.show_dots), s.showSettingsDots) { v -> vm.update { it.copy(showSettingsDots = v) } }
            TextButton(onClick = { vm.openHidden() }) { Text(stringResource(R.string.hidden_open, s.hidden.size)) }

            Section(R.string.sec_allapps)
            Text(stringResource(R.string.trigger_label), style = MaterialTheme.typography.bodySmall)
            ChoiceRow(
                listOf(
                    AllAppsTrigger.TOUCH to stringResource(R.string.trigger_touch),
                    AllAppsTrigger.HOLD to stringResource(R.string.trigger_hold),
                    AllAppsTrigger.OFF to stringResource(R.string.trigger_off)
                ),
                s.allAppsTrigger
            ) { m -> vm.update { it.copy(allAppsTrigger = m) } }
            when (s.allAppsTrigger) {
                AllAppsTrigger.TOUCH -> Text(stringResource(R.string.touch_hint), style = MaterialTheme.typography.bodySmall)
                AllAppsTrigger.HOLD -> Text(stringResource(R.string.hold_hint), style = MaterialTheme.typography.bodySmall)
                AllAppsTrigger.OFF -> Unit
            }
            SwitchRow(stringResource(R.string.swipe_opens), s.swipeOpensList) { v -> vm.update { it.copy(swipeOpensList = v) } }
            SwitchRow(stringResource(R.string.scrub_natural), s.scrubNatural) { v -> vm.update { it.copy(scrubNatural = v) } }
            SliderRow(stringResource(R.string.edge_zone), s.edgeZoneDp.toFloat(), 0f..120f, { "${it.roundToInt()} dp" }) { v ->
                vm.update { it.copy(edgeZoneDp = v.roundToInt()) }
            }
            SliderRow(stringResource(R.string.scrub_reach), s.scrubReachPct.toFloat(), 40f..100f, { "${it.roundToInt()} %" }) { v ->
                vm.update { it.copy(scrubReachPct = v.roundToInt()) }
            }
            SwitchRow(stringResource(R.string.show_private), s.showPrivateSpace) { v ->
                vm.update { it.copy(showPrivateSpace = v) }
            }
            SwitchRow(stringResource(R.string.frequent_show), s.showFrequent) { v -> vm.update { it.copy(showFrequent = v) } }
            if (s.showFrequent) {
                SliderRow(stringResource(R.string.frequent_count), s.frequentCount.toFloat(), 3f..8f, { "${it.roundToInt()}" }, steps = 4) { v ->
                    vm.update { it.copy(frequentCount = v.roundToInt()) }
                }
                TextButton(onClick = { vm.resetUsage() }) { Text(stringResource(R.string.frequent_reset)) }
            }

            Section(R.string.sec_haptics)
            Text(stringResource(R.string.haptic_label), style = MaterialTheme.typography.bodySmall)
            ChoiceRow(
                listOf(
                    HapticLevel.OFF to stringResource(R.string.haptic_off),
                    HapticLevel.LIGHT to stringResource(R.string.haptic_light),
                    HapticLevel.MEDIUM to stringResource(R.string.haptic_medium),
                    HapticLevel.STRONG to stringResource(R.string.haptic_strong)
                ),
                s.hapticLevel
            ) { l -> vm.update { it.copy(hapticLevel = l) } }
            Text(stringResource(R.string.haptic_hint), style = MaterialTheme.typography.bodySmall)

            Section(R.string.sec_gestures)
            SwitchRow(stringResource(R.string.double_tap_lock), s.doubleTapLock) { v -> vm.update { it.copy(doubleTapLock = v) } }
            if (s.doubleTapLock) LockAdminSettings(vm)

            Section(R.string.sec_position)
            Text(stringResource(R.string.side_label), style = MaterialTheme.typography.bodySmall)
            ChoiceRow(
                listOf(AlignMode.START to stringResource(R.string.left), AlignMode.END to stringResource(R.string.right)),
                s.alignment
            ) { m -> vm.update { it.copy(alignment = m) } }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.vertical_label), style = MaterialTheme.typography.bodySmall)
            ChoiceRow(
                listOf(
                    VerticalAlign.TOP to stringResource(R.string.top),
                    VerticalAlign.CENTER to stringResource(R.string.center),
                    VerticalAlign.BOTTOM to stringResource(R.string.bottom)
                ),
                s.verticalAlign
            ) { m -> vm.update { it.copy(verticalAlign = m) } }
            SliderRow(stringResource(R.string.margin), s.edgeMarginDp.toFloat(), 0f..120f, { "${it.roundToInt()} dp" }) { v ->
                vm.update { it.copy(edgeMarginDp = v.roundToInt()) }
            }
            SwitchRow(stringResource(R.string.avoid_gestures), s.avoidGestureZone) { v -> vm.update { it.copy(avoidGestureZone = v) } }
            SliderRow(stringResource(R.string.icon_tap_margin), s.iconTapMarginDp.toFloat(), 0f..120f, { "${it.roundToInt()} dp" }) { v ->
                vm.update { it.copy(iconTapMarginDp = v.roundToInt()) }
            }

            Section(R.string.sec_appearance)
            ChoiceRow(
                listOf(
                    LabelMode.ICON_ONLY to stringResource(R.string.icon_only),
                    LabelMode.ICON_AND_NAME to stringResource(R.string.icon_name)
                ),
                s.labelMode
            ) { m -> vm.update { it.copy(labelMode = m) } }
            SliderRow(stringResource(R.string.icon_size), s.iconSizeDp.toFloat(), 32f..72f, { "${it.roundToInt()} dp" }) { v ->
                vm.update { it.copy(iconSizeDp = v.roundToInt()) }
            }
            SliderRow(stringResource(R.string.spacing), s.spacingDp.toFloat(), 0f..28f, { "${it.roundToInt()} dp" }) { v ->
                vm.update { it.copy(spacingDp = v.roundToInt()) }
            }
            SliderRow(stringResource(R.string.ui_opacity), s.uiOpacity, 0.3f..1f, { "${(it * 100).roundToInt()} %" }) { v ->
                vm.update { it.copy(uiOpacity = v) }
            }
            SwitchRow(stringResource(R.string.notif_dots), s.notificationDots) { v -> vm.update { it.copy(notificationDots = v) } }
            if (s.notificationDots) NotificationAccessSettings()

            Section(R.string.sec_header)
            HeaderEditor(stringResource(R.string.clock), s.clock, 16..96) { c -> vm.update { it.copy(clock = c) } }
            HeaderEditor(stringResource(R.string.weekday), s.weekday, 10..48) { c -> vm.update { it.copy(weekday = c) } }
            HeaderEditor(stringResource(R.string.date), s.date, 10..48) { c -> vm.update { it.copy(date = c) } }
            HeaderEditor(stringResource(R.string.battery), s.battery, 10..48) { c -> vm.update { it.copy(battery = c) } }
            HeaderEditor(stringResource(R.string.alarm), s.alarm, 10..48) { c -> vm.update { it.copy(alarm = c) } }
            HeaderEditor(stringResource(R.string.weather), s.weather, 10..32) { c ->
                val toggled = c.show != s.weather.show
                vm.update { it.copy(weather = c) }
                if (toggled) {
                    vm.applyWeatherSchedule()
                    vm.refreshWeather(force = true)
                }
            }
            if (s.weather.show) {
                SliderRow(stringResource(R.string.weather_icon_size), s.weatherIconDp.toFloat(), 32f..96f, { "${it.roundToInt()} dp" }) { v ->
                    vm.update { it.copy(weatherIconDp = v.roundToInt()) }
                }
                WeatherSettings(vm, s)
            }

            Section(R.string.sec_background)
            ChoiceRow(
                listOf(
                    WallpaperMode.DARK to stringResource(R.string.bg_dark),
                    WallpaperMode.SYSTEM to stringResource(R.string.bg_system),
                    WallpaperMode.IMAGE to stringResource(R.string.bg_image)
                ),
                s.wallpaperMode
            ) { m -> vm.update { it.copy(wallpaperMode = m) } }
            TextButton(onClick = {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(stringResource(R.string.pick_image)) }
            SliderRow(stringResource(R.string.dim), s.scrimAlpha, 0f..0.9f, { "${(it * 100).roundToInt()} %" }) { v ->
                vm.update { it.copy(scrimAlpha = v) }
            }

            Section(R.string.sec_icons)
            TextButton(onClick = { vm.openIconPacks() }) {
                Text(
                    if (s.iconPack == null) stringResource(R.string.icons_none)
                    else stringResource(R.string.icons_pack, s.iconPack ?: "")
                )
            }

            Section(R.string.sec_backup)
            TextButton(onClick = { exportFile.launch("hashira-settings.json") }) { Text(stringResource(R.string.backup_export)) }
            TextButton(onClick = { importFile.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                Text(stringResource(R.string.backup_import))
            }
            Text(stringResource(R.string.backup_hint), style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.footer, appVersion()), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** State of a system grant that is re-checked when returning from the permissions screen. */
@Composable
private fun rememberResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return tick
}

@Composable
private fun LockAdminSettings(vm: LauncherViewModel) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val resume = rememberResumeTick()
    val active = remember(tick, resume) { vm.isLockAdminActive() }

    if (active) {
        Text(stringResource(R.string.admin_active), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { vm.removeLockAdmin(); tick++ }) { Text(stringResource(R.string.admin_remove)) }
    } else {
        Text(
            stringResource(R.string.admin_inactive),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
        TextButton(onClick = {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, vm.lockAdminComponent)
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.admin_explanation))
            context.startActivity(intent)
        }) { Text(stringResource(R.string.admin_grant)) }
    }
}

/** Notification dots need the user to grant "notification access" in Android. */
@Composable
private fun NotificationAccessSettings() {
    val context = LocalContext.current
    val resume = rememberResumeTick()
    val granted = remember(resume) {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
    Text(
        stringResource(R.string.notif_hint),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp)
    )
    if (granted) {
        Text(stringResource(R.string.notif_granted), style = MaterialTheme.typography.bodySmall)
    } else {
        TextButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }) { Text(stringResource(R.string.notif_grant)) }
    }
}
