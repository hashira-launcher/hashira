package com.hashira.launcher.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hashira.launcher.BiometricGate
import com.hashira.launcher.LauncherViewModel
import com.hashira.launcher.R

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * List of hidden apps. If protection is on, fingerprint or PIN is requested on entry:
 * without confirming, nothing is shown and it goes back to settings.
 */
@Composable
fun HiddenAppsScreen(vm: LauncherViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    val authTitle = stringResource(R.string.auth_title)
    val authSubtitle = stringResource(R.string.auth_subtitle)
    var unlocked by remember { mutableStateOf(!s.lockHidden) }
    BackHandler { vm.openSettings() }

    LaunchedEffect(Unit) {
        if (!unlocked) {
            if (activity == null) {
                vm.openSettings()
            } else {
                BiometricGate.authenticate(activity, authTitle, authSubtitle) { ok ->
                    if (ok) unlocked = true else vm.openSettings()
                }
            }
        }
    }

    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        if (!unlocked) return@Surface // until confirmed, the screen stays empty

        val hidden = remember(apps, s.hidden) { apps.filter { it.key in s.hidden } }
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
        ) {
            item(key = "head") {
                Column {
                    TextButton(onClick = { vm.openSettings() }) { Text(stringResource(R.string.back_settings)) }
                    Text(stringResource(R.string.hidden_title), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    SwitchRow(stringResource(R.string.hidden_protect), s.lockHidden) { v ->
                        vm.update { it.copy(lockHidden = v) }
                    }
                    Text(stringResource(R.string.hidden_protect_hint), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    if (hidden.isNotEmpty()) {
                        TextButton(onClick = { vm.unhideAll() }) { Text(stringResource(R.string.hidden_unhide_all)) }
                    } else {
                        Text(stringResource(R.string.hidden_empty), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            items(hidden, key = { it.key }) { e ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(e, s, vm, null, sizeDp = 32)
                    Spacer(Modifier.width(12.dp))
                    Text(e.label, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.unhide(e.key) }) { Text(stringResource(R.string.hidden_unhide)) }
                }
            }
        }
    }
}
