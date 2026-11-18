package com.hashira.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hashira.launcher.LauncherViewModel
import com.hashira.launcher.R
import com.hashira.launcher.data.ProfileKind
import kotlin.math.roundToInt

private const val ROW_HEIGHT_DP = 56

/**
 * Choose which apps go on the home screen and in what order. Reorder by dragging the ≡ handle (the other rows
 * make room by themselves); ✕ removes the app. Private Space is left out on purpose.
 */
@Composable
fun FavoritesScreen(vm: LauncherViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    BackHandler { vm.openSettings() }

    val visible = remember(apps, s.hidden) {
        apps.filter { it.key !in s.hidden && it.profile != ProfileKind.PRIVATE }
    }
    val byKey = remember(visible) { visible.associateBy { it.key } }
    val saved = remember(byKey, s.favorites) { s.favorites.mapNotNull { byKey[it] } }

    // Local copy while dragging; the final order is saved on release.
    var order by remember { mutableStateOf(saved) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(saved) { if (dragKey == null) order = saved }
    val favSet = remember(order) { order.map { it.key }.toSet() }
    val rowPx = with(LocalDensity.current) { ROW_HEIGHT_DP.dp.toPx() }

    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
        ) {
            item(key = "head") {
                Column {
                    TextButton(onClick = { vm.openSettings() }) { Text(stringResource(R.string.back_settings)) }
                    Text(stringResource(R.string.fav_title), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.fav_desc), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (order.isNotEmpty()) {
                item(key = "favs_title") {
                    Text(
                        stringResource(R.string.fav_order_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                items(order, key = { "f_${it.key}" }) { e ->
                    val dragging = e.key == dragKey
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(ROW_HEIGHT_DP.dp)
                            .then(
                                if (dragging) {
                                    Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset }
                                } else {
                                    Modifier.animateItem()
                                }
                            )
                            .background(if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Drag handle: each time the finger passes half a row, it swaps with its neighbor.
                        Box(
                            Modifier
                                .size(44.dp, ROW_HEIGHT_DP.dp)
                                .pointerInput(e.key) {
                                    detectDragGestures(
                                        onDragStart = { dragKey = e.key; dragOffset = 0f },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount.y
                                            val i = order.indexOfFirst { it.key == e.key }
                                            val step = (dragOffset / rowPx).roundToInt().coerceIn(-1, 1)
                                            val j = (i + step).coerceIn(0, order.lastIndex)
                                            if (i >= 0 && j != i) {
                                                order = order.toMutableList().also { it.add(j, it.removeAt(i)) }
                                                dragOffset -= (j - i) * rowPx
                                            }
                                        },
                                        onDragEnd = {
                                            vm.setFavoritesOrder(order.map { it.key })
                                            dragKey = null
                                            dragOffset = 0f
                                        },
                                        onDragCancel = {
                                            dragKey = null
                                            dragOffset = 0f
                                            order = saved
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("≡", fontSize = 26.sp, color = MaterialTheme.colorScheme.secondary)
                        }
                        AppIcon(e, s, vm, null, sizeDp = 32)
                        Spacer(Modifier.width(12.dp))
                        Text(e.label, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.setFavorite(e.key, false) }) { Text("✕") }
                    }
                }
            }

            item(key = "all_title") {
                Text(
                    stringResource(R.string.fav_all_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
                )
            }
            if (visible.isEmpty()) {
                item(key = "empty") { Text(stringResource(R.string.no_apps)) }
            }
            items(visible, key = { "a_${it.key}" }, contentType = { "check" }) { e ->
                val checked = e.key in favSet
                Row(
                    Modifier.fillMaxWidth().clickable { vm.setFavorite(e.key, !checked) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    AppIcon(e, s, vm, null, sizeDp = 32)
                    Spacer(Modifier.width(12.dp))
                    Text(e.label)
                }
            }
        }
    }
}
