package com.hashira.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.hashira.launcher.LauncherViewModel
import com.hashira.launcher.data.LauncherSettings
import com.hashira.launcher.weather.WeatherIconHit
import com.hashira.launcher.weather.WeatherKind

/**
 * Weather icon: the one from the selected icon pack (detected by name or chosen by hand) and, if the pack
 * has none for that condition, the outline icon drawn by the app.
 */
@Composable
internal fun WeatherGlyph(kind: WeatherKind, s: LauncherSettings, vm: LauncherViewModel, sizeDp: Int) {
    val px = with(LocalDensity.current) { sizeDp.dp.roundToPx() }
    var hit by remember(kind, px, s.iconPack, s.weatherUsePackIcons, s.weatherIconOverrides) {
        mutableStateOf<WeatherIconHit?>(vm.peekWeatherIcon(kind, px))
    }
    LaunchedEffect(kind, px, s.iconPack, s.weatherUsePackIcons, s.weatherIconOverrides) {
        if (hit == null) hit = vm.loadWeatherIcon(kind, px)
    }
    val bmp = hit?.bmp
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(sizeDp.dp))
    } else {
        WeatherIcon(kind, sizeDp)
    }
}
