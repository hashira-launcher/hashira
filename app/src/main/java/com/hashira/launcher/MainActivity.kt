package com.hashira.launcher

import android.app.LocaleManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.LocaleList
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hashira.launcher.data.AppLanguage
import com.hashira.launcher.ui.HashiraTheme
import com.hashira.launcher.ui.FavoritesScreen
import com.hashira.launcher.ui.HiddenAppsScreen
import com.hashira.launcher.ui.HomeScreen
import com.hashira.launcher.ui.IconPackScreen
import com.hashira.launcher.ui.LocalNotificationDots
import com.hashira.launcher.ui.SettingsScreen
import com.hashira.launcher.ui.WeatherIconsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        window.isNavigationBarContrastEnforced = false
        applyLanguage(vm.settings.value.language)
        setContent { HashiraTheme { Root(vm) } }
        // "hashira · Settings" entry (rescue access from another launcher)
        if (savedInstanceState == null && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) vm.openSettings()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.settings.map { it.language }.distinctUntilChanged().collect { applyLanguage(it) }
                }
                launch {
                    vm.messages.collect { m ->
                        val text = if (m.arg != null) getString(m.res, m.arg) else getString(m.res)
                        Toast.makeText(this@MainActivity, text, Toast.LENGTH_SHORT).show()
                    }
                }
                // While the launcher is visible, weather refreshes at the chosen interval
                launch {
                    while (true) {
                        delay(vm.settings.value.weatherIntervalMin * 60_000L)
                        vm.refreshWeather()
                    }
                }
            }
        }
    }

    /** Per-app language (Android 13+). English by default; changing it recreates the Activity. */
    private fun applyLanguage(language: AppLanguage) {
        val lm = getSystemService(LocaleManager::class.java)
        val wanted = LocaleList.forLanguageTags(language.tag)
        if (lm.applicationLocales.toLanguageTags() != wanted.toLanguageTags()) {
            lm.applicationLocales = wanted
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshWeather()
    }

    // Pressing Home while in settings returns to the main screen.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) vm.goHome()
        else if (intent.hasCategory(Intent.CATEGORY_LAUNCHER)) vm.openSettings()
    }
}

@Composable
private fun Root(vm: LauncherViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val dots by NotificationDots.packages.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalNotificationDots provides dots) {
        when (screen) {
            Screen.HOME -> HomeScreen(vm)
            Screen.SETTINGS -> SettingsScreen(vm)
            Screen.ICON_PACKS -> IconPackScreen(vm)
            Screen.FAVORITES -> FavoritesScreen(vm)
            Screen.HIDDEN -> HiddenAppsScreen(vm)
            Screen.WEATHER_ICONS -> WeatherIconsScreen(vm)
        }
    }
}
