package com.hashira.launcher.ui

import android.content.pm.ShortcutInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hashira.launcher.LauncherViewModel
import com.hashira.launcher.R
import com.hashira.launcher.data.AppEntry
import com.hashira.launcher.data.LauncherSettings
import com.hashira.launcher.data.ProfileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Custom name for an app. Empty or equal to the original = back to the system name. */
@Composable
internal fun RenameDialog(vm: LauncherViewModel, entry: AppEntry, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(entry.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title, entry.originalLabel)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.rename_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { vm.renameApp(entry, text); onDismiss() }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { vm.renameApp(entry, null); onDismiss() }) {
                    Text(stringResource(R.string.rename_restore))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}

/**
 * Action when swiping the icon toward the screen center: a shortcut of the app itself, another app, or nothing.
 */
@Composable
internal fun SwipeActionDialog(vm: LauncherViewModel, s: LauncherSettings, entry: AppEntry, onDismiss: () -> Unit) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val shortcuts by produceState<List<ShortcutInfo>>(emptyList(), entry.key) {
        value = withContext(Dispatchers.IO) { vm.appRepo.shortcuts(entry) }
    }
    var query by remember { mutableStateOf("") }
    val candidates = remember(apps, query) {
        val q = query.trim()
        apps.filter { it.key != entry.key && it.profile != ProfileKind.PRIVATE && (q.isEmpty() || it.label.contains(q, ignoreCase = true)) }
            .take(60)
    }
    fun choose(spec: String?) {
        vm.setSwipeAction(entry, spec)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.swipe_title, entry.label)) },
        text = {
            Column {
                Text(stringResource(R.string.swipe_action_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                if (s.swipeActions.containsKey(entry.key)) {
                    TextButton(onClick = { choose(null) }) { Text(stringResource(R.string.swipe_none)) }
                }
                if (shortcuts.isNotEmpty()) {
                    Text(stringResource(R.string.swipe_shortcuts), style = MaterialTheme.typography.titleSmall)
                    shortcuts.forEach { sc ->
                        Text(
                            sc.shortLabel?.toString() ?: sc.id,
                            modifier = Modifier.fillMaxWidth().clickable { choose("sc:${sc.id}") }.padding(vertical = 10.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text(stringResource(R.string.swipe_other_app), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                LazyColumn(Modifier.height(200.dp)) {
                    items(candidates, key = { it.key }) { app ->
                        Text(
                            app.label,
                            modifier = Modifier.fillMaxWidth().clickable { choose("app:${app.key}") }.padding(vertical = 10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}
