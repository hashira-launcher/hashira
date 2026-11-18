package com.hashira.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hashira.launcher.LauncherViewModel
import com.hashira.launcher.R
import com.hashira.launcher.icons.IconPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun IconPackScreen(vm: LauncherViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val packs by vm.packs.collectAsStateWithLifecycle()
    val error by vm.packError.collectAsStateWithLifecycle()
    BackHandler { vm.openSettings() }

    val selected = s.iconPack
    val selectedMissing = selected != null && packs.none { it.packageName == selected }

    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            TextButton(onClick = { vm.openSettings() }) { Text(stringResource(R.string.back_settings)) }
            Text(stringResource(R.string.packs_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.packs_desc), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))

            if (selectedMissing) {
                Text(stringResource(R.string.packs_missing), color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(8.dp))
            }
            error?.let {
                Text(stringResource(R.string.pack_error, it), color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(8.dp))
            }

            PackRow(stringResource(R.string.packs_system), selected == null) { vm.selectIconPack(null) }
            packs.forEach { p ->
                PackRow(p.label, selected == p.packageName) { vm.selectIconPack(p.packageName) }
            }
            if (packs.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.packs_none), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { vm.refreshPacks() }) { Text(stringResource(R.string.packs_rescan)) }
        }
    }
}

@Composable
private fun PackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

/**
 * Full-screen icon picker for an icon pack, with search (packs ship hundreds of icons).
 * Used both for an app's icon and for the weather icons: returns the chosen name.
 * To keep it smooth: filtering waits until you stop typing, icons are cached in memory and
 * on disk, and only the visible ones are decoded, a few at a time.
 */
@Composable
fun IconPickerDialog(
    vm: LauncherViewModel,
    packageName: String,
    title: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val result by produceState<Result<IconPack>?>(null, packageName) {
        value = withContext(Dispatchers.IO) { vm.packManager.getResult(packageName) }
    }
    var query by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        delay(150)
        applied = query.trim()
    }
    val cellPx = with(LocalDensity.current) { CELL_DP.dp.roundToPx() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.picker_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                val r = result
                when {
                    r == null -> CircularProgressIndicator()
                    r.isFailure -> Text(stringResource(R.string.picker_failed, r.exceptionOrNull()?.message ?: ""))
                    else -> {
                        val pack = r.getOrThrow()
                        val names = remember(pack, applied) {
                            if (applied.isEmpty()) pack.pickerNames
                            else pack.pickerNames.filter { it.contains(applied, ignoreCase = true) }
                        }
                        when {
                            pack.pickerNames.isEmpty() -> Text(stringResource(R.string.picker_empty))
                            names.isEmpty() -> Text(stringResource(R.string.picker_no_match))
                            else -> LazyVerticalGrid(GridCells.Adaptive(64.dp), Modifier.fillMaxSize()) {
                                items(names, key = { it }, contentType = { 0 }) { name ->
                                    PackIconCell(vm, packageName, name, cellPx) { onPick(name); onDismiss() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val CELL_DP = 48

@Composable
private fun PackIconCell(vm: LauncherViewModel, pack: String, name: String, sizePx: Int, onClick: () -> Unit) {
    val loader = vm.iconLoader
    var bmp by remember(name, sizePx) { mutableStateOf(loader.peekPackIcon(pack, name, sizePx)) }
    LaunchedEffect(name, sizePx) {
        if (bmp == null) bmp = loader.packIcon(pack, name, sizePx)
    }
    Box(Modifier.padding(4.dp).size((CELL_DP + 8).dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        bmp?.let { Image(bitmap = it, contentDescription = name, modifier = Modifier.size(CELL_DP.dp)) }
    }
}
