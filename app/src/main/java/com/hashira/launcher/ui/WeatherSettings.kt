package com.hashira.launcher.ui

import android.Manifest
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hashira.launcher.GeoStatus
import com.hashira.launcher.LauncherViewModel
import com.hashira.launcher.R
import com.hashira.launcher.WeatherStatus
import com.hashira.launcher.data.LauncherSettings
import com.hashira.launcher.data.WeatherSource
import java.util.Date

private val INTERVALS = listOf(15 to "15 min", 30 to "30 min", 60 to "1 h", 180 to "3 h", 360 to "6 h")

/** Weather settings: interval (also in background), location, units, icons and status. */
@Composable
internal fun WeatherSettings(vm: LauncherViewModel, s: LauncherSettings) {
    val context = LocalContext.current
    val weather by vm.weather.collectAsStateWithLifecycle()
    val status by vm.weatherStatus.collectAsStateWithLifecycle()
    val detail by vm.weatherDetail.collectAsStateWithLifecycle()
    val results by vm.geoResults.collectAsStateWithLifecycle()
    val geoStatus by vm.geoStatus.collectAsStateWithLifecycle()
    val busy by vm.geoBusy.collectAsStateWithLifecycle()
    val lang = LocalConfiguration.current.locales[0].language
    var query by remember { mutableStateOf("") }
    var permTick by remember { mutableIntStateOf(0) }
    val hasPerm = remember(permTick) { vm.deviceLocation.hasPermission() }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permTick++
        if (granted) vm.refreshWeather(force = true)
    }

    // Update interval
    Text(stringResource(R.string.weather_interval), style = MaterialTheme.typography.bodySmall)
    ChoiceRow(INTERVALS, s.weatherIntervalMin) { m ->
        vm.update { it.copy(weatherIntervalMin = m) }
        vm.applyWeatherSchedule()
    }
    Text(
        stringResource(R.string.weather_background_note),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp)
    )
    weather?.let {
        Text(
            stringResource(R.string.weather_last_update, DateFormat.getTimeFormat(context).format(Date(it.fetchedAt))),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
    Spacer(Modifier.height(12.dp))

    // Location
    Text(stringResource(R.string.wx_location), style = MaterialTheme.typography.bodySmall)
    ChoiceRow(
        listOf(
            WeatherSource.CITY to stringResource(R.string.loc_city),
            WeatherSource.DEVICE to stringResource(R.string.loc_device)
        ),
        s.weatherSource
    ) { m ->
        vm.update { it.copy(weatherSource = m) }
        vm.refreshWeather(force = true)
    }

    if (s.weatherSource == WeatherSource.CITY) {
        if (s.weatherCity.isNotBlank()) {
            Text(stringResource(R.string.current_city, s.weatherCity), modifier = Modifier.padding(top = 8.dp))
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.search_city)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.searchCity(query, lang) }),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        TextButton(onClick = { vm.searchCity(query, lang) }, enabled = !busy && query.isNotBlank()) {
            Text(stringResource(if (busy) R.string.searching else R.string.search))
        }
        results.forEach { r ->
            Text(
                r.display,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.selectCity(r); query = "" }
                    .padding(vertical = 10.dp)
            )
        }
        geoStatus?.let {
            Text(
                stringResource(if (it == GeoStatus.NO_RESULTS) R.string.geo_none else R.string.geo_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    } else if (hasPerm) {
        Text(stringResource(R.string.perm_granted), style = MaterialTheme.typography.bodySmall)
    } else {
        Text(
            stringResource(R.string.perm_hint),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )
        TextButton(onClick = { permLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
            Text(stringResource(R.string.perm_button))
        }
    }

    SwitchRow(stringResource(R.string.fahrenheit), s.weatherFahrenheit) { v ->
        vm.update { it.copy(weatherFahrenheit = v) }
    }
    TextButton(onClick = { vm.openWeatherIcons() }) { Text(stringResource(R.string.weather_icons_button)) }

    status?.let {
        Text(
            stringResource(
                when (it) {
                    WeatherStatus.PICK_CITY -> R.string.ws_pick_city
                    WeatherStatus.NO_PERMISSION -> R.string.ws_no_permission
                    WeatherStatus.NO_LOCATION -> R.string.ws_no_location
                    WeatherStatus.FAILED -> R.string.ws_failed
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
    detail?.let {
        Text(
            stringResource(R.string.weather_detail, it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
    TextButton(onClick = { vm.refreshWeather(force = true) }) { Text(stringResource(R.string.update_now)) }
    Text(stringResource(R.string.weather_credit), style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
}
