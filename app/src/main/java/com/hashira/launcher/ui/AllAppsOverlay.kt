package com.hashira.launcher.ui

import android.os.UserHandle
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hashira.launcher.LauncherViewModel
import com.hashira.launcher.R
import com.hashira.launcher.data.AlignMode
import com.hashira.launcher.data.AppEntry
import com.hashira.launcher.data.HapticLevel
import com.hashira.launcher.data.LauncherSettings
import com.hashira.launcher.data.ProfileInfo
import com.hashira.launcher.data.ProfileKind
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

private val STRIP: List<String> = listOf("★") + ('A'..'Z').map { it.toString() } + "#"
private val LETTER_KEYS: Set<String> = STRIP.drop(1).toSet()
private const val SCRUB_HOLD_MS = 150L // pause before a slide that turns into letter scrubbing
private const val PRIVATE_KEY = "private"
private const val FREQ_KEY = "freq"

/** Height (0..1 of the list area) at which the chosen letter's apps are shown: the middle, to reach them comfortably with the thumb. */
private const val CENTER = 0.5f

/** Strip on this side of the list taken by the letter strip (36 dp strip + breathing room). */
private val STRIP_GUTTER = 44.dp
private val MARKS = Regex("\\p{Mn}+")

/** Each item computes its key once when the list is built (not on every measure while scrolling). */
private sealed interface OverlayItem {
    val key: String
    val section: String
    val type: Int

    data class Header(val id: String) : OverlayItem {
        override val key = "h_$id"
        override val section = id
        override val type = 0
    }

    data class App(val entry: AppEntry, override val section: String) : OverlayItem {
        override val key = "a_${section}_${entry.key}"
        override val type = 1
    }

    data class PrivateToggle(val user: UserHandle, val locked: Boolean) : OverlayItem {
        override val key = "p_toggle"
        override val section = PRIVATE_KEY
        override val type = 2
    }
}

/** Flattened list + where each strip letter starts (letters without apps point to the next letter that has some). */
private class OverlayModel(
    val items: List<OverlayItem>,
    val sectionStart: Map<String, Int>,
    private val starts: IntArray // one entry per STRIP letter
) {
    /** Strip letter under a finger height of 0..1 over the strip. */
    fun letterAt(fraction: Float): Int = (fraction.coerceIn(0f, 0.9999f) * STRIP.size).toInt()

    /** Index of the first item shown for a strip letter. */
    fun startOf(letter: Int): Int = starts[letter]

    /** End (exclusive) of the block that starts at [start]: up to the next letter or Private Space header. */
    fun endOf(start: Int): Int {
        var i = start + 1
        while (i < items.size) {
            val item = items[i]
            if (item is OverlayItem.Header && (item.id == PRIVATE_KEY || item.id in LETTER_KEYS)) break
            i++
        }
        return i
    }
}

/**
 * Full A–Z list. At the top, full width, the search field; below, the list on the icons' side and the
 * letter strip on the opposite side. Scrubbing the strip (by touching it or with the same finger that opened the list)
 * jumps to the letter under the finger and shows that letter's apps in the middle of the list.
 *
 * Performance: the finger position is read only inside snapshotFlow and layout lambdas, so moving the
 * finger does not recompose this screen; it only recomposes when the letter under the finger changes.
 */
@Composable
internal fun AllAppsOverlay(
    vm: LauncherViewModel,
    s: LauncherSettings,
    apps: List<AppEntry>,
    profiles: List<ProfileInfo>,
    holdY: () -> Float?,
    insets: WindowInsets,
    actions: AppActions,
    onClose: () -> Unit
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val listAtStart = s.alignment == AlignMode.START
    val horizontal = if (listAtStart) Alignment.Start else Alignment.End
    val stripSide = if (listAtStart) Alignment.CenterEnd else Alignment.CenterStart

    val model = remember(apps, profiles, usage, s.hidden, s.favorites, s.showPrivateSpace, s.showFrequent, s.frequentCount) {
        buildModel(apps, profiles, usage, s)
    }

    var query by remember { mutableStateOf("") }
    val searching = query.isNotBlank()
    val searchingNow by rememberUpdatedState(searching)
    val results = remember(query, apps, s.hidden, s.showPrivateSpace) {
        if (searching) searchApps(query, apps, s) else emptyList()
    }
    // Back (button or edge swipe) never closes the list: it only clears the search field.
    // The list closes when opening an app or pressing Home.
    BackHandler(enabled = true) { if (query.isNotEmpty()) query = "" }
    val resultsState = rememberLazyListState()
    LaunchedEffect(query) { resultsState.scrollToItem(0) }

    var touchY by remember { mutableStateOf<Float?>(null) }
    var stripTop by remember { mutableFloatStateOf(0f) }
    var stripHeight by remember { mutableFloatStateOf(0f) }
    var stripLeft by remember { mutableFloatStateOf(0f) }
    var stripRight by remember { mutableFloatStateOf(0f) }
    var listHeight by remember { mutableFloatStateOf(0f) }
    var listTop by remember { mutableFloatStateOf(0f) }
    val fingerY = remember { mutableFloatStateOf(Float.NaN) } // NaN = not scrubbing
    var scrubSection by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableStateOf<Float?>(null) } // finger resting anywhere (observe only)
    var scrubDragY by remember { mutableStateOf<Float?>(null) } // a new touch that moved vertically: it scrubs like the first one
    val natural = s.scrubNatural
    val reach = s.scrubReachPct / 100f

    // Heights learned from the list itself ([0] = header, [1] = app row), used to center a whole letter block.
    val rowSizes = remember { floatArrayOf(0f, 0f) }

    // The finger picks a letter and that letter's apps are shown in the middle of the list, wherever the finger is.
    // The list only jumps when the letter under the finger changes, so it never runs ahead of or behind the finger.
    LaunchedEffect(model, natural, reach) {
        var letter = -1
        snapshotFlow {
            when {
                searchingNow || stripHeight <= 0f -> null // when the strip is measured it emits again: the first movement is not lost
                touchY != null -> touchY // over the strip, the finger goes straight to the letter
                else -> (scrubDragY ?: holdY())?.let { y ->
                    // Less travel: the finger covers the whole list moving less, around the center of the strip.
                    // Natural mode: the list follows the finger, so the travel is mirrored.
                    val f = ((y - stripTop) / stripHeight).coerceIn(0f, 1f)
                    val g = ((f - 0.5f) / reach + 0.5f).coerceIn(0f, 1f)
                    stripTop + (if (natural) 1f - g else g) * stripHeight
                }
            }
        }.collect { y ->
            if (y == null || stripHeight <= 0f || model.items.isEmpty()) {
                fingerY.floatValue = Float.NaN
                scrubSection = null
                letter = -1
                return@collect
            }
            fingerY.floatValue = y
            val next = model.letterAt(((y - stripTop) / stripHeight).coerceIn(0f, 1f))
            if (next == letter) return@collect
            letter = next

            val start = model.startOf(next)
            scrubSection = model.items[start].section.let { if (it == FREQ_KEY) "★" else it }

            listState.layoutInfo.visibleItemsInfo.forEach {
                val key = it.key as? String ?: return@forEach
                if (key.startsWith("h_")) rowSizes[0] = it.size.toFloat() else if (key.startsWith("a_")) rowSizes[1] = it.size.toFloat()
            }
            val header = if (rowSizes[0] > 0f) rowSizes[0] else with(density) { 48.dp.toPx() }
            val row = if (rowSizes[1] > 0f) rowSizes[1] else with(density) { 64.dp.toPx() }
            var block = 0f
            for (i in start until model.endOf(start)) block += if (model.items[i] is OverlayItem.Header) header else row

            // The top padding measures exactly CENTER: at offset 0 the block starts in the center.
            // Half the block height puts the block's middle there; tall blocks stop near the top edge.
            val maxOffset = (listHeight * CENTER - with(density) { 12.dp.toPx() }).coerceAtLeast(0f)
            listState.scrollToItem(start, (block / 2f).coerceIn(0f, maxOffset).roundToInt())
        }
    }

    // When opened without scrubbing (swipe sideways) the list starts at the top, without the centering gap
    LaunchedEffect(listHeight > 0f) {
        if (listHeight > 0f && fingerY.floatValue.isNaN()) listState.scrollToItem(0, (listHeight * CENTER).roundToInt())
    }

    // Dragging the list with the finger highlights the letter just like scrubbing: the section crossing the center
    LaunchedEffect(model) {
        snapshotFlow {
            val y = dragY
            if (searchingNow || y == null || touchY != null || scrubDragY != null || holdY() != null || !listState.isScrollInProgress) {
                null
            } else {
                val info = listState.layoutInfo
                val mid = info.viewportStartOffset + info.viewportSize.height / 2
                val item = info.visibleItemsInfo.firstOrNull { it.offset + it.size > mid }
                if (item == null) null else y to (model.items.getOrNull(item.index) ?: model.items.lastOrNull())?.section
            }
        }.collect { v ->
            if (v != null) {
                fingerY.floatValue = v.first
                scrubSection = v.second
            } else if (touchY == null && scrubDragY == null && holdY() == null) {
                fingerY.floatValue = Float.NaN
                scrubSection = null
            }
        }
    }

    val haptic = remember(s.hapticLevel) { hapticConstant(s.hapticLevel) }
    LaunchedEffect(scrubSection) {
        if (scrubSection != null && haptic != null) view.performHapticFeedback(haptic)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    dragY = down.position.y
                    // On the list (not on the strip, not on the search field):
                    // - slide right away: the list scrolls normally.
                    // - touch, pause a moment, then slide vertically: scrub the letters by finger height,
                    //   like the touch that opened the list.
                    // A plain tap still opens the app.
                    val onStrip = down.position.x in stripLeft..stripRight && down.position.y in stripTop..(stripTop + stripHeight)
                    val canScrub = !searchingNow && !onStrip && down.position.y >= listTop
                    var decided = false
                    var scrubbing = false
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        dragY = change.position.y
                        if (canScrub && !decided) {
                            val d = change.position - down.position
                            if (abs(d.y) > viewConfiguration.touchSlop || abs(d.x) > viewConfiguration.touchSlop) {
                                decided = true // the first real movement decides: scroll or scrub
                                scrubbing = abs(d.y) >= abs(d.x) && change.uptimeMillis - down.uptimeMillis >= SCRUB_HOLD_MS
                            }
                        }
                        if (scrubbing) {
                            change.consume()
                            scrubDragY = change.position.y
                        }
                    }
                    dragY = null
                    scrubDragY = null
                }
            }
            // Dragging on the list scrolls it, except for a slide that turns into scrubbing (see above)
            .scrollable(if (searching) resultsState else listState, Orientation.Vertical)
            // Tapping on a gap does not close the list; it only prevents the tap from reaching the home screen below
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(insets)) {
            SearchField(
                query = query,
                onQuery = { query = it },
                onSearch = { results.firstOrNull()?.let { vm.launch(it); onClose() } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        val b = it.boundsInRoot()
                        listHeight = b.height
                        listTop = b.top
                    }
                    .padding(horizontal = s.edgeMarginDp.dp)
            ) {
                if (searching) {
                    LazyColumn(
                        state = resultsState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = horizontal,
                        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
                    ) {
                        if (results.isEmpty()) {
                            item(key = "no_results") {
                                Text(
                                    stringResource(R.string.search_empty),
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 15.sp,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                        items(results, key = { "r_${it.key}" }, contentType = { "app" }) { entry ->
                            AppRow(entry, s, vm, actions, forceName = true, shadowed = false, fillWidth = true, onAfterLaunch = onClose)
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        // Full width (minus the strip): touching or dragging next to a name still counts
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (listAtStart) PaddingValues(end = STRIP_GUTTER) else PaddingValues(start = STRIP_GUTTER)),
                        horizontalAlignment = horizontal,
                        // Top and bottom: half the list, so the first and the last letters can also be centered.
                        contentPadding = PaddingValues(
                            top = with(density) { (listHeight * CENTER).toDp() },
                            bottom = with(density) { (listHeight * CENTER).toDp() }
                        )
                    ) {
                        items(model.items, key = { it.key }, contentType = { it.type }) { item ->
                            when (item) {
                                is OverlayItem.Header -> Text(
                                    when (item.id) {
                                        PRIVATE_KEY -> stringResource(R.string.private_space)
                                        FREQ_KEY -> stringResource(R.string.frequent_title)
                                        else -> item.id
                                    },
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(
                                        top = (s.spacingDp / 2 + 8).dp, bottom = (s.spacingDp / 2).dp,
                                        start = 6.dp, end = 6.dp
                                    )
                                )
                                is OverlayItem.App ->
                                    AppRow(item.entry, s, vm, actions, forceName = true, shadowed = false, fillWidth = true, onAfterLaunch = onClose)
                                is OverlayItem.PrivateToggle -> Text(
                                    stringResource(if (item.locked) R.string.private_locked else R.string.private_lock),
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontSize = 15.sp,
                                    modifier = Modifier
                                        .clickable { vm.setProfileLocked(item.user, !item.locked) }
                                        .padding(vertical = 12.dp, horizontal = 6.dp)
                                )
                            }
                        }
                        item(key = "settings") {
                            Text(
                                stringResource(R.string.overlay_settings),
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 15.sp,
                                modifier = Modifier.clickable { vm.openSettings() }.padding(vertical = 14.dp, horizontal = 6.dp)
                            )
                        }
                    }

                    // Letter strip: wave toward the inside; letters without apps are dimmed
                    Column(
                        modifier = Modifier
                            .align(stripSide)
                            .fillMaxHeight(0.92f)
                            .width(36.dp)
                            .onGloballyPositioned {
                                val b = it.boundsInRoot()
                                stripTop = b.top
                                stripHeight = b.height
                                stripLeft = b.left
                                stripRight = b.right
                            }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    down.consume()
                                    touchY = stripTop + down.position.y
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        change.consume()
                                        touchY = stripTop + change.position.y
                                    }
                                    touchY = null
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        STRIP.forEachIndexed { i, key ->
                            val active = key == scrubSection
                            // Equal slots: each letter sits exactly where the finger mapping expects it
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                key,
                                fontSize = 13.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    active -> Color.White
                                    key in model.sectionStart -> Color.White.copy(alpha = 0.6f)
                                    else -> Color.White.copy(alpha = 0.22f)
                                },
                                // The wave is computed in the layout phase: nothing recomposes when moving the finger
                                modifier = Modifier.offset {
                                    val y = fingerY.floatValue
                                    if (y.isNaN() || stripHeight <= 0f) {
                                        IntOffset.Zero
                                    } else {
                                        val center = ((y - stripTop) / stripHeight).coerceIn(0f, 1f) * STRIP.size - 0.5f
                                        val d = i - center
                                        val shift = (exp(-(d * d) / (2f * 2.5f * 2.5f)) * 48.dp.toPx()).roundToInt()
                                        IntOffset(if (listAtStart) -shift else shift, 0)
                                    }
                                }
                            )
                            }
                        }
                    }
                }
            }
        }

        // Bubble with the current letter, next to the finger
        val section = scrubSection
        if (section != null) {
            Box(
                Modifier
                    .offset {
                        val y = fingerY.floatValue
                        val x = if (listAtStart) stripLeft - (48 + 60 + 12).dp.toPx() else stripRight + (48 + 12).dp.toPx()
                        IntOffset(x.roundToInt(), ((if (y.isNaN()) 0f else y) - 30.dp.toPx()).roundToInt())
                    }
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Color(0xE61C1C1C)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when (section) {
                        PRIVATE_KEY -> "P"
                        FREQ_KEY -> "•"
                        else -> section
                    },
                    color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onSearch: () -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                Text("✕", color = Color.White, modifier = Modifier.clickable { onQuery("") }.padding(12.dp))
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }), // Enter opens the first result
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = Color.White,
            focusedBorderColor = Color.White,
            unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
            focusedPlaceholderColor = Color.White.copy(alpha = 0.5f),
            unfocusedPlaceholderColor = Color.White.copy(alpha = 0.5f)
        ),
        modifier = modifier
    )
}

private fun hapticConstant(level: HapticLevel): Int? = when (level) {
    HapticLevel.OFF -> null
    HapticLevel.LIGHT -> HapticFeedbackConstants.CLOCK_TICK
    HapticLevel.MEDIUM -> HapticFeedbackConstants.CONTEXT_CLICK
    HapticLevel.STRONG -> HapticFeedbackConstants.LONG_PRESS
}

private fun buildModel(
    apps: List<AppEntry>,
    profiles: List<ProfileInfo>,
    usage: Map<String, Int>,
    s: LauncherSettings
): OverlayModel {
    val visible = apps.filter { it.key !in s.hidden && it.profile != ProfileKind.PRIVATE }
    val byKey = visible.associateBy { it.key }
    val favs = s.favorites.mapNotNull { byKey[it] }
    val favKeys = favs.map { it.key }.toSet()
    val groups = visible.groupBy { sectionOf(it.label) }
    val priv = profiles.firstOrNull { it.kind == ProfileKind.PRIVATE }

    // Frequent: the most opened from hashira (at least 2 times), without repeating favorites
    val frequent = if (!s.showFrequent) emptyList() else usage.entries
        .filter { it.value >= 2 && it.key !in favKeys }
        .sortedByDescending { it.value }
        .mapNotNull { byKey[it.key] }
        .take(s.frequentCount)

    val items = buildList {
        if (frequent.isNotEmpty()) {
            add(OverlayItem.Header(FREQ_KEY))
            frequent.forEach { add(OverlayItem.App(it, FREQ_KEY)) }
        }
        if (favs.isNotEmpty()) {
            add(OverlayItem.Header("★"))
            favs.forEach { add(OverlayItem.App(it, "★")) }
        }
        STRIP.drop(1).forEach { key ->
            val group = groups[key] ?: return@forEach
            add(OverlayItem.Header(key))
            group.forEach { add(OverlayItem.App(it, key)) }
        }
        if (s.showPrivateSpace && priv != null) {
            add(OverlayItem.Header(PRIVATE_KEY))
            if (priv.locked) {
                add(OverlayItem.PrivateToggle(priv.user, true))
            } else {
                apps.filter { it.profile == ProfileKind.PRIVATE && it.key !in s.hidden }
                    .forEach { add(OverlayItem.App(it, PRIVATE_KEY)) }
                add(OverlayItem.PrivateToggle(priv.user, false))
            }
        }
    }

    val sectionStart = HashMap<String, Int>()
    items.forEachIndexed { index, item -> if (item is OverlayItem.Header) sectionStart[item.id] = index }

    val starts = IntArray(STRIP.size)
    var next = items.indexOfLast { it is OverlayItem.Header }.coerceAtLeast(0) // trailing empty letters show the last block
    for (i in STRIP.size - 1 downTo 0) {
        next = sectionStart[STRIP[i]] ?: next
        starts[i] = next
    }
    starts[0] = 0 // top of the strip = top of everything (where the frequent apps are)
    return OverlayModel(items, sectionStart, starts)
}

/** Initial letter without accents; anything that is not A–Z goes to "#". */
private fun sectionOf(label: String): String {
    val c = Normalizer.normalize(label.trim(), Normalizer.Form.NFD).firstOrNull { !it.isWhitespace() }
        ?: return "#"
    val u = c.uppercaseChar()
    return if (u in 'A'..'Z') u.toString() else "#"
}

private fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase()

/** Match by content, ignoring case and accents; those starting with the text come first. */
private fun searchApps(query: String, apps: List<AppEntry>, s: LauncherSettings): List<AppEntry> {
    val q = fold(query.trim())
    return apps
        .filter { it.key !in s.hidden && (it.profile != ProfileKind.PRIVATE || s.showPrivateSpace) }
        .map { it to fold(it.label) }
        .filter { (_, folded) -> folded.contains(q) }
        .sortedWith(compareBy<Pair<AppEntry, String>>({ !it.second.startsWith(q) }, { it.second }))
        .map { it.first }
}
