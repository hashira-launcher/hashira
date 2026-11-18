package com.hashira.launcher.ui

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.hashira.launcher.LauncherViewModel
import com.hashira.launcher.R
import com.hashira.launcher.data.AlignMode
import com.hashira.launcher.data.AllAppsTrigger
import com.hashira.launcher.data.AppEntry
import com.hashira.launcher.data.HPos
import com.hashira.launcher.data.LabelMode
import com.hashira.launcher.data.LauncherSettings
import com.hashira.launcher.data.ProfileKind
import com.hashira.launcher.data.VerticalAlign
import com.hashira.launcher.data.WallpaperMode
import com.hashira.launcher.weather.WeatherData
import com.hashira.launcher.weather.formatTemp
import com.hashira.launcher.weather.weatherDesc
import com.hashira.launcher.weather.weatherKind
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/** "Hold" mode: short press to open the full list. */
private const val HOLD_MS = 300L

/** "Tap" mode with double-tap to lock: minimum delay so the first tap does not open the list. */
private const val DOUBLE_TAP_GUARD_MS = 220L

@Composable
fun HomeScreen(vm: LauncherViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val wallpaper by vm.wallpaper.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val homeTick by vm.homeTick.collectAsStateWithLifecycle()

    var overlayOpen by remember { mutableStateOf(false) }
    var holdY by remember { mutableStateOf<Float?>(null) }
    var pickerFor by remember { mutableStateOf<AppEntry?>(null) }
    var renameFor by remember { mutableStateOf<AppEntry?>(null) }
    var swipeFor by remember { mutableStateOf<AppEntry?>(null) }
    var listBounds by remember { mutableStateOf<Rect?>(null) }
    val actions = remember {
        AppActions(
            pickIcon = { pickerFor = it },
            rename = { renameFor = it },
            swipeAction = { swipeFor = it }
        )
    }
    val listState = rememberLazyListState()
    val currentSettings by rememberUpdatedState(s)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    LaunchedEffect(homeTick) {
        overlayOpen = false
        listState.scrollToItem(0)
    }
    // Back on the home screen does nothing: a launcher must never close (it would leave an empty screen).
    BackHandler(enabled = true) { }

    val trigger = s.allAppsTrigger
    val swipeEnabled = s.swipeOpensList
    // TOUCH: opens on tap (0 ms). HOLD: after a short press. With double-tap to lock enabled,
    // TOUCH waits a moment to be able to tell apart the second tap.
    val touchEnabled = trigger != AllAppsTrigger.OFF
    val triggerDelay = when {
        trigger == AllAppsTrigger.HOLD -> HOLD_MS
        s.doubleTapLock -> DOUBLE_TAP_GUARD_MS
        else -> 0L
    }
    val swipeThresholdPx = with(density) { 72.dp.toPx() }
    val iconsAtStart = s.alignment == AlignMode.START
    val horizontal = if (iconsAtStart) Alignment.Start else Alignment.End
    val insets = if (s.avoidGestureZone) {
        WindowInsets.safeDrawing.union(WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal))
    } else {
        WindowInsets.safeDrawing
    }

    Box(Modifier.fillMaxSize()) {
        // Home layer: the free-area gestures live here
        Box(
            Modifier
                .fillMaxSize()
                // Long press -> settings (only if "hold" is not active) and/or double-tap -> lock
                .pointerInput(touchEnabled, s.doubleTapLock) {
                    if (!touchEnabled || s.doubleTapLock) {
                        detectTapGestures(
                            onLongPress = if (!touchEnabled) ({ _: Offset -> vm.openSettings() }) else null,
                            onDoubleTap = if (s.doubleTapLock) ({ _: Offset -> vm.lockScreen() }) else null
                        )
                    }
                }
                // Swipe sideways -> full list
                .pointerInput(swipeEnabled) {
                    if (swipeEnabled) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onHorizontalDrag = { _, dx ->
                                total += dx
                                if (abs(total) > swipeThresholdPx) {
                                    total = 0f
                                    overlayOpen = true
                                }
                            }
                        )
                    }
                }
                // Tap (or hold) on any free space, with a single finger: the list opens immediately
                // and, without lifting, moving the finger up and down scrubs through the letters. Except over the icon column.
                .pointerInput(touchEnabled, triggerDelay, trigger, s.edgeZoneDp, iconsAtStart) {
                    if (touchEnabled) {
                        detectTouchThenScrub(
                            delayMs = triggerDelay,
                            moveTriggers = trigger != AllAppsTrigger.HOLD,
                            edgeZonePx = s.edgeZoneDp.dp.toPx(),
                            atStart = iconsAtStart,
                            isExcluded = { p -> isOverIcons(p, listBounds, listState, currentSettings, density) },
                            onTriggered = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                overlayOpen = true
                            },
                            onMove = { holdY = it },
                            onEnd = { holdY = null }
                        )
                    }
                }
        ) {
            Background(s, wallpaper)
            if (s.scrimAlpha > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = s.scrimAlpha)))

            Column(
                Modifier
                    .fillMaxSize()
                    // alpha creates an offscreen layer: only applied if it was actually used
                    .then(if (s.uiOpacity < 1f) Modifier.alpha(s.uiOpacity) else Modifier)
                    .windowInsetsPadding(insets)
                    .padding(horizontal = s.edgeMarginDp.dp, vertical = 12.dp),
                horizontalAlignment = horizontal
            ) {
                HeaderBlock(s, weather, vm) { vm.openSettings() }
                Spacer(Modifier.height(20.dp))
                AppColumn(
                    modifier = Modifier
                        .weight(1f)
                        .onGloballyPositioned { listBounds = it.boundsInRoot() },
                    s = s,
                    apps = apps,
                    vm = vm,
                    actions = actions,
                    listState = listState
                )
            }
        }

        if (overlayOpen) {
            AllAppsOverlay(
                vm = vm,
                s = s,
                apps = apps,
                profiles = profiles,
                holdY = { holdY }, // read only inside the list: moving the finger does not recompose the home screen
                insets = insets,
                actions = actions,
                onClose = { overlayOpen = false }
            )
        }
    }

    val pack = s.iconPack
    pickerFor?.let { entry ->
        if (pack != null) {
            IconPickerDialog(
                vm = vm,
                packageName = pack,
                title = stringResource(R.string.picker_title, entry.label),
                onPick = { vm.setOverride(entry, it) },
                onDismiss = { pickerFor = null }
            )
        }
    }
    renameFor?.let { RenameDialog(vm, it) { renameFor = null } }
    swipeFor?.let { SwipeActionDialog(vm, s, it) { swipeFor = null } }
}

/**
 * Does the touch fall on the favorite icons or in their protected zone? The zone starts at the column edge
 * (not the screen edge: the free margin opens the list) and extends past the icons (plus the
 * margin chosen in settings), only at row height. The list does not open there.
 */
private fun isOverIcons(p: Offset, bounds: Rect?, list: LazyListState, s: LauncherSettings, density: Density): Boolean {
    val rows = list.layoutInfo.visibleItemsInfo
    if (bounds == null || rows.isEmpty()) return false
    return with(density) {
        val slack = 8.dp.toPx()
        val top = bounds.top + rows.first().offset - slack
        val bottom = bounds.top + rows.last().offset + rows.last().size + slack
        val rowWidth = if (s.labelMode == LabelMode.ICON_AND_NAME) minOf(bounds.width, 310.dp.toPx())
        else (s.iconSizeDp + 12).dp.toPx() + ROW_HIT_EXTRA.toPx()
        val reach = rowWidth + s.iconTapMarginDp.dp.toPx()
        // Only counts from the column edge: the screen margin (free gap) opens the list
        when {
            p.y < top || p.y > bottom -> false
            s.alignment == AlignMode.START -> p.x >= bounds.left - slack && p.x <= bounds.left + reach
            else -> p.x <= bounds.right + slack && p.x >= bounds.right - reach
        }
    }
}

/**
 * Watches the finger without consuming anything until the trigger is met: on touch (delayMs = 0) or after a
 * short press. Does not trigger over the app column, which keeps its own gestures. Once the list is open,
 * scrubbing through the letters only starts when the finger moves vertically; so a simple tap leaves
 * the list where it was, instead of jumping to the letter matching that screen height.
 */
private suspend fun PointerInputScope.detectTouchThenScrub(
    delayMs: Long,
    moveTriggers: Boolean,
    edgeZonePx: Float,
    atStart: Boolean,
    isExcluded: (Offset) -> Boolean,
    onTriggered: () -> Unit,
    onMove: (Float) -> Unit,
    onEnd: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        // The edge strip takes priority over the protected zone of the icons
        val inZone = if (atStart) down.position.x < edgeZonePx else down.position.x > size.width - edgeZonePx
        if (!inZone && isExcluded(down.position)) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop * 2f
        val scrubSlop = viewConfiguration.touchSlop
        // The free screen margin opens the list instantly, whatever mode is chosen
        val instant = inZone

        if (delayMs > 0L && !instant) {
            // true = cancel (released), false = open now (moved vertically), null = time elapsed
            val outcome = withTimeoutOrNull(delayMs) {
                var result: Boolean? = null
                while (result == null) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    result = when {
                        change == null || !change.pressed || change.isConsumed -> true
                        (change.position - down.position).getDistance() > slop -> {
                            val d = change.position - down.position
                            !(moveTriggers && abs(d.y) > abs(d.x)) // vertical movement does not wait: it opens and scrubs
                        }
                        else -> null
                    }
                }
                result
            }
            if (outcome == true) return@awaitEachGesture
        }

        onTriggered()
        var scrubbing = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            change.consume()
            if (!scrubbing && abs(change.position.y - down.position.y) > scrubSlop) scrubbing = true
            if (scrubbing) onMove(change.position.y)
        }
        onEnd()
    }
}

@Composable
private fun Background(s: LauncherSettings, wallpaper: ImageBitmap?) {
    val dark = Modifier.fillMaxSize().background(
        Brush.verticalGradient(listOf(Color(0xFF000000), Color(0xFF0E0E0E), Color(0xFF000000)))
    )
    when (s.wallpaperMode) {
        WallpaperMode.SYSTEM -> Unit // the window is transparent and shows the system wallpaper
        WallpaperMode.DARK -> Box(dark)
        WallpaperMode.IMAGE ->
            if (wallpaper != null) {
                Image(wallpaper, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(dark)
            }
    }
}

@Composable
private fun HeaderBlock(s: LauncherSettings, weather: WeatherData?, vm: LauncherViewModel, onOpenSettings: () -> Unit) {
    if (!s.clock.show && !s.date.show && !s.weekday.show && !s.battery.show && !s.alarm.show && !s.weather.show) return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableStateOf(LocalDateTime.now()) }

    // Only updated while the screen is visible (saves battery).
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = LocalDateTime.now()
                delay(60_000L - System.currentTimeMillis() % 60_000L)
            }
        }
    }

    val locale = LocalConfiguration.current.locales[0] // app's active language
    val timeFmt = remember(locale) {
        DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", locale)
    }
    val weekdayFmt = remember(locale) { DateTimeFormatter.ofPattern("EEEE", locale) }
    val dateFmt = remember(locale, s.weekday.show) {
        // If the weekday is shown separately, the date goes without it.
        val skeleton = if (s.weekday.show) "dMMM" else "EEEdMMM"
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }

    HeaderLayout(
        Modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onOpenSettings() }) }
    ) {
        HPos.values().forEach { pos ->
            val clock = s.clock.takeIf { it.show && it.pos == pos }
            val weekday = s.weekday.takeIf { it.show && it.pos == pos }
            val date = s.date.takeIf { it.show && it.pos == pos }
            val battery = s.battery.takeIf { it.show && it.pos == pos }
            val alarm = s.alarm.takeIf { it.show && it.pos == pos }
            val wx = s.weather.takeIf { it.show && it.pos == pos }
            if (clock == null && weekday == null && date == null && battery == null && alarm == null && wx == null) {
                return@forEach
            }

            Column(
                Modifier.layoutId(pos),
                horizontalAlignment = when (pos) {
                    HPos.LEFT -> Alignment.Start
                    HPos.CENTER -> Alignment.CenterHorizontally
                    HPos.RIGHT -> Alignment.End
                }
            ) {
                clock?.let {
                    Text(
                        now.format(timeFmt),
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = it.sizeSp.sp,
                        fontWeight = FontWeight.Light,
                        style = Legible
                    )
                }
                weekday?.let {
                    Text(
                        now.format(weekdayFmt).replaceFirstChar { c -> c.titlecase(locale) },
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = it.sizeSp.sp,
                        style = Legible
                    )
                }
                date?.let {
                    Text(
                        now.format(dateFmt).replaceFirstChar { c -> c.titlecase(locale) },
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = it.sizeSp.sp,
                        style = Legible
                    )
                }
                battery?.let { BatteryText(it.sizeSp) }
                alarm?.let { AlarmText(it.sizeSp, locale) }
                wx?.let { item ->
                    if (weather == null) {
                        Text(
                            stringResource(R.string.weather_none),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = item.sizeSp.sp,
                            style = Legible
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WeatherGlyph(weatherKind(weather.code), s, vm, s.weatherIconDp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    "${stringResource(weatherDesc(weather.code))} ${formatTemp(weather.tempC, s.weatherFahrenheit)}",
                                    color = Color.White.copy(alpha = 0.92f),
                                    fontSize = item.sizeSp.sp,
                                    style = Legible
                                )
                                Text(
                                    "${formatTemp(weather.maxC, s.weatherFahrenheit)}/${formatTemp(weather.minC, s.weatherFahrenheit)}",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = (item.sizeSp * 0.9f).sp,
                                    style = Legible
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Distributes the header zones (left, center, right) into rows. If two zones would overlap in the
 * same row (for example weather in the center and alarm on the left), the second moves to the row below:
 * this way all elements can be enabled at once without overlapping.
 */
@Composable
private fun HeaderLayout(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 16.dp.roundToPx()
        val zones = measurables.map { m ->
            m.measure(constraints.copy(minWidth = 0, minHeight = 0)) to (m.layoutId as? HPos ?: HPos.LEFT)
        }

        fun left(z: Pair<Placeable, HPos>) = when (z.second) {
            HPos.LEFT -> 0
            HPos.CENTER -> (width - z.first.width) / 2
            HPos.RIGHT -> width - z.first.width
        }

        fun overlap(a: Pair<Placeable, HPos>, b: Pair<Placeable, HPos>) =
            left(a) + a.first.width + gap > left(b) && left(b) + b.first.width + gap > left(a)

        val rows = ArrayList<MutableList<Pair<Placeable, HPos>>>()
        for (zone in zones) {
            val row = rows.lastOrNull()
            if (row != null && row.none { overlap(it, zone) }) row.add(zone) else rows.add(mutableListOf(zone))
        }

        val height = rows.sumOf { row -> row.maxOf { it.first.height } }
        layout(width, height) {
            var y = 0
            for (row in rows) {
                row.forEach { it.first.placeRelative(left(it), y) }
                y += row.maxOf { it.first.height }
            }
        }
    }
}

/** Battery: no permissions (the system delivers the state as a sticky broadcast). */
@Composable
private fun BatteryText(sizeSp: Int) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(batteryText(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { text = batteryText(intent) }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    Text(text, color = Color.White.copy(alpha = 0.75f), fontSize = sizeSp.sp, style = Legible)
}

private fun batteryText(intent: Intent?): String {
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    if (level < 0 || scale <= 0) return "—"
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return "${level * 100 / scale}%" + if (charging) " +" else ""
}

/** Next alarm from the system clock: also needs no permissions. */
@Composable
private fun AlarmText(sizeSp: Int, locale: Locale) {
    val context = LocalContext.current
    val alarmManager = remember { context.getSystemService(AlarmManager::class.java) }
    var next by remember { mutableStateOf(alarmManager.nextAlarmClock?.triggerTime) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { next = alarmManager.nextAlarmClock?.triggerTime }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    val time = next ?: return
    val pattern = DateFormat.getBestDateTimePattern(locale, if (DateFormat.is24HourFormat(context)) "EEEHm" else "EEEhm")
    Text(
        stringResource(R.string.alarm_next, SimpleDateFormat(pattern, locale).format(Date(time))),
        color = Color.White.copy(alpha = 0.75f),
        fontSize = sizeSp.sp,
        style = Legible
    )
}

@Composable
private fun AppColumn(
    modifier: Modifier,
    s: LauncherSettings,
    apps: List<AppEntry>,
    vm: LauncherViewModel,
    actions: AppActions,
    listState: LazyListState
) {
    // Private Space never appears on the home screen, only in the A–Z list.
    val visible = remember(apps, s.hidden) {
        apps.filter { it.key !in s.hidden && it.profile != ProfileKind.PRIVATE }
    }
    val byKey = remember(visible) { visible.associateBy { it.key } }
    val favs = remember(byKey, s.favorites) { s.favorites.mapNotNull { byKey[it] } }
    val favKeys = remember(favs) { favs.map { it.key }.toSet() }
    val rest = remember(visible, favKeys) { visible.filter { it.key !in favKeys } }
    val showRest = !s.onlyFavorites
    val horizontal = if (s.alignment == AlignMode.START) Alignment.Start else Alignment.End
    val vertical = when (s.verticalAlign) {
        VerticalAlign.TOP -> Alignment.Top
        VerticalAlign.CENTER -> Alignment.CenterVertically
        VerticalAlign.BOTTOM -> Alignment.Bottom
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        horizontalAlignment = horizontal,
        verticalArrangement = Arrangement.spacedBy(0.dp, vertical) // spacing lives inside each row
    ) {
        items(favs, key = { it.key }, contentType = { "app" }) { AppRow(it, s, vm, actions) }

        if (favs.isNotEmpty() && showRest && rest.isNotEmpty()) {
            item(key = "divider", contentType = "divider") {
                Box(
                    Modifier
                        .padding(vertical = (s.spacingDp / 2).dp)
                        .width(24.dp).height(1.dp)
                        .background(Color.White.copy(alpha = 0.25f))
                )
            }
        }
        if (showRest) {
            items(rest, key = { it.key }, contentType = { "app" }) { AppRow(it, s, vm, actions) }
        }
        if (s.onlyFavorites && favs.isEmpty()) {
            item(key = "hint") {
                Column(horizontalAlignment = horizontal) {
                    Text(
                        stringResource(R.string.hint_no_favorites),
                        color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
                        style = Legible, modifier = Modifier.widthIn(max = 260.dp)
                    )
                    Text(
                        stringResource(R.string.action_pick_favorites),
                        color = Color.White, fontSize = 16.sp, style = Legible,
                        modifier = Modifier.clickable { vm.openFavorites() }.padding(vertical = 12.dp)
                    )
                }
            }
        }
        if (visible.isEmpty() && !s.onlyFavorites) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.no_apps),
                    color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, style = Legible
                )
            }
        }
        // "···" button at the end, centered under the icons (same width as an icon row)
        if (s.showSettingsDots) {
            item(key = "dots") {
                Box(
                    Modifier
                        .padding(top = (s.spacingDp / 2).dp)
                        .width((s.iconSizeDp + 12).dp)
                        .heightIn(min = 48.dp)
                        .clickable { vm.openSettings() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("···", color = Color.White.copy(alpha = 0.85f), fontSize = 24.sp, style = Legible)
                }
            }
        }
    }
}
