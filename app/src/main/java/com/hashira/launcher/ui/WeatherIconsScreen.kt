package com.hashira.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hashira.launcher.LauncherViewModel
import com.hashira.launcher.R
import com.hashira.launcher.weather.WeatherKind
import com.hashira.launcher.weather.weatherKindLabel

/** Weather icons from the pack: automatic detection by name and manual choice for each condition. */
@Composable
fun WeatherIconsScreen(vm: LauncherViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    var pickerFor by remember { mutableStateOf<WeatherKind?>(null) }
    BackHandler { vm.openSettings() }

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
            Text(stringResource(R.string.wi_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.wi_hint), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))

            val pack = s.iconPack
            if (pack == null) {
                Text(stringResource(R.string.wi_no_pack), color = MaterialTheme.colorScheme.secondary)
            } else {
                SwitchRow(stringResource(R.string.wi_use_pack), s.weatherUsePackIcons) { v ->
                    vm.update { it.copy(weatherUsePackIcons = v) }
                }
            }
            Spacer(Modifier.height(8.dp))

            WeatherKind.values().forEach { kind ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    WeatherGlyph(kind, s, vm, 48)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(weatherKindLabel(kind)), modifier = Modifier.weight(1f))
                    if (pack != null) {
                        TextButton(onClick = { pickerFor = kind }) { Text(stringResource(R.string.wi_choose)) }
                        if (s.weatherIconOverrides.containsKey(kind.name)) {
                            TextButton(onClick = { vm.setWeatherIcon(kind, null) }) { Text(stringResource(R.string.wi_reset)) }
                        }
                    }
                }
            }
        }
    }

    val pack = s.iconPack
    pickerFor?.let { kind ->
        if (pack != null) {
            IconPickerDialog(
                vm = vm,
                packageName = pack,
                title = stringResource(weatherKindLabel(kind)),
                onPick = { vm.setWeatherIcon(kind, it) },
                onDismiss = { pickerFor = null }
            )
        }
    }
}
