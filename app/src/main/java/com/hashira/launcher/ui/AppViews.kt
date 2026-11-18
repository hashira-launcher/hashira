package com.hashira.launcher.ui

import android.content.pm.ShortcutInfo
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hashira.launcher.LauncherViewModel
import com.hashira.launcher.R
import com.hashira.launcher.data.AlignMode
import com.hashira.launcher.data.AppEntry
import com.hashira.launcher.data.LabelMode
import com.hashira.launcher.data.LauncherSettings
import com.hashira.launcher.data.ProfileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Soft, cheap shadow (a large blur on every text hurts scrolling smoothness).
internal val Legible = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1.5f), 6f))

/** Packages with active notifications (provided by MainActivity). */
internal val LocalNotificationDots = compositionLocalOf { emptySet<String>() }

/** Extra touch area of each row toward the screen center (invisible, only enlarges the touch target). */
internal val ROW_HIT_EXTRA: Dp = 24.dp

/** Menu actions of an app that need their own dialog in the host screen. */
@Immutable
internal class AppActions(
    val pickIcon: (AppEntry) -> Unit,
    val rename: (AppEntry) -> Unit,
    val swipeAction: (AppEntry) -> Unit
)

/** App row reused on the home screen, the full list and search results. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppRow(
    entry: AppEntry,
    s: LauncherSettings,
    vm: LauncherViewModel,
    actions: AppActions,
    forceName: Boolean = false,
    shadowed: Boolean = true,
    fillWidth: Boolean = false,
    onAfterLaunch: () -> Unit = {}
) {
    var menu by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val start = s.alignment == AlignMode.START
    val showName = forceName || s.labelMode == LabelMode.ICON_AND_NAME
    val haptics = LocalHapticFeedback.current
    val hasSwipe = s.swipeActions.containsKey(entry.key)
    val towardCenter = if (start) 1f else -1f
    val swipeThreshold = with(LocalDensity.current) { 56.dp.toPx() }

    // Row spacing lives inside the row so there are no dead gaps between rows when tapping.
    Box(if (fillWidth) Modifier.fillMaxWidth() else Modifier) {
        Row(
            Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .heightIn(min = (48 + s.spacingDp).dp)
                .then(
                    if (hasSwipe) {
                        // Swiping the icon toward the screen center runs the chosen action
                        Modifier.pointerInput(entry.key, towardCenter) {
                            var total = 0f
                            var fired = false
                            detectHorizontalDragGestures(
                                onDragStart = { total = 0f; fired = false },
                                onHorizontalDrag = { _, dx ->
                                    total += dx
                                    if (!fired && total * towardCenter > swipeThreshold) {
                                        fired = true
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        vm.runSwipeAction(entry)
                                        onAfterLaunch()
                                    }
                                }
                            )
                        }
                    } else {
                        Modifier
                    }
                )
                // No ripple or rounded clipping: with hundreds of rows, every extra layer shows up while scrolling
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { vm.launch(entry); onAfterLaunch() },
                    onLongClick = { menu = true }
                )
                .padding(
                    start = if (start || fillWidth) 6.dp else 6.dp + ROW_HIT_EXTRA,
                    end = if (!start || fillWidth) 6.dp else 6.dp + ROW_HIT_EXTRA,
                    top = 2.dp, bottom = 2.dp
                ),
            horizontalArrangement = if (start) Arrangement.Start else Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val name: @Composable () -> Unit = {
                Text(
                    entry.label,
                    color = Color.White,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = if (shadowed) Legible else TextStyle.Default,
                    modifier = Modifier.widthIn(max = 220.dp)
                )
            }
            val desc = if (showName) null else entry.label
            if (start) {
                AppIcon(entry, s, vm, desc)
                if (showName) { Spacer(Modifier.width(14.dp)); name() }
            } else {
                if (showName) { name(); Spacer(Modifier.width(14.dp)) }
                AppIcon(entry, s, vm, desc)
            }
        }

        // The menu only exists while open: with hundreds of rows, one per row is expensive.
        if (menu) {
            AppMenu(entry, s, vm, actions, onAfterLaunch) { menu = false }
        }
    }
}

@Composable
private fun AppMenu(
    entry: AppEntry,
    s: LauncherSettings,
    vm: LauncherViewModel,
    actions: AppActions,
    onAfterLaunch: () -> Unit,
    onDismiss: () -> Unit
) {
    val isFav = entry.key in s.favorites
    val canPin = entry.profile != ProfileKind.PRIVATE // Private Space does not go on the home screen
    val shortcuts by produceState<List<ShortcutInfo>>(emptyList(), entry.key) {
        value = withContext(Dispatchers.IO) { vm.appRepo.shortcuts(entry) }
    }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        shortcuts.forEach { sc ->
            DropdownMenuItem(
                text = { Text(sc.shortLabel?.toString() ?: sc.id) },
                onClick = { onDismiss(); vm.startShortcut(sc); onAfterLaunch() }
            )
        }
        if (shortcuts.isNotEmpty()) HorizontalDivider()

        DropdownMenuItem(text = { Text(stringResource(R.string.menu_info)) },
            onClick = { onDismiss(); vm.openAppInfo(entry) })
        if (canPin) {
            DropdownMenuItem(
                text = { Text(stringResource(if (isFav) R.string.menu_unpin else R.string.menu_pin)) },
                onClick = { onDismiss(); vm.toggleFavorite(entry.key) })
        }
        DropdownMenuItem(text = { Text(stringResource(R.string.menu_hide)) },
            onClick = { onDismiss(); vm.hide(entry.key) })
        DropdownMenuItem(text = { Text(stringResource(R.string.menu_rename)) },
            onClick = { onDismiss(); actions.rename(entry) })
        DropdownMenuItem(text = { Text(stringResource(R.string.menu_swipe_action)) },
            onClick = { onDismiss(); actions.swipeAction(entry) })
        if (s.iconPack != null) {
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_change_icon)) },
                onClick = { onDismiss(); actions.pickIcon(entry) })
            if (entry.key in s.iconOverrides) {
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_reset_icon)) },
                    onClick = { onDismiss(); vm.setOverride(entry, null) })
            }
        }
        DropdownMenuItem(text = { Text(stringResource(R.string.menu_settings)) },
            onClick = { onDismiss(); vm.openSettings() })
    }
}

/**
 * Icon with synchronous access to the memory cache (drawn in the first frame, no flicker)
 * and asynchronous loading from disk or render only when needed. White dot if there are notifications.
 */
@Composable
internal fun AppIcon(
    entry: AppEntry,
    s: LauncherSettings,
    vm: LauncherViewModel,
    description: String?,
    sizeDp: Int = s.iconSizeDp
) {
    val px = with(LocalDensity.current) { sizeDp.dp.roundToPx() }
    val override = s.iconOverrides[entry.key]
    val loader = vm.iconLoader
    var bmp by remember(entry.key, s.iconPack, override, px) {
        mutableStateOf(loader.peek(entry, s.iconPack, override, px))
    }
    LaunchedEffect(entry.key, s.iconPack, override, px) {
        if (bmp == null) bmp = loader.load(entry, s.iconPack, override, px)
    }
    val showDot = s.notificationDots && entry.packageName in LocalNotificationDots.current

    Box(Modifier.size(sizeDp.dp)) {
        bmp?.let { Image(bitmap = it, contentDescription = description, modifier = Modifier.fillMaxSize()) }
        if (showDot) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size((sizeDp * 0.24f).dp)
                    .background(Color.White, CircleShape)
                    .border(1.5.dp, Color.Black, CircleShape)
            )
        }
    }
}
