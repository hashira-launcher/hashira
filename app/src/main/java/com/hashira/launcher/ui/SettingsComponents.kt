package com.hashira.launcher.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hashira.launcher.R
import com.hashira.launcher.data.HPos
import com.hashira.launcher.data.HeaderItem
import kotlin.math.roundToInt

/** Pieces reused by all settings screens. */

@Composable
internal fun Section(@StringRes title: Int) {
    Spacer(Modifier.height(20.dp))
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.secondary
    )
    Spacer(Modifier.height(8.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

/**
 * The value is committed on release: while dragging only the displayed number changes.
 * This way settings are not rewritten nor icons reloaded on every pixel of the slide.
 */
@Composable
internal fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(display(local), style = MaterialTheme.typography.bodySmall)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
            steps = steps
        )
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun HeaderEditor(title: String, item: HeaderItem, sizeRange: IntRange, onChange: (HeaderItem) -> Unit) {
    SwitchRow(title, item.show) { onChange(item.copy(show = it)) }
    if (item.show) {
        SliderRow(
            stringResource(R.string.element_size), item.sizeSp.toFloat(),
            sizeRange.first.toFloat()..sizeRange.last.toFloat(),
            { "${it.roundToInt()} sp" }
        ) { onChange(item.copy(sizeSp = it.roundToInt())) }
        ChoiceRow(
            listOf(
                HPos.LEFT to stringResource(R.string.left),
                HPos.CENTER to stringResource(R.string.center),
                HPos.RIGHT to stringResource(R.string.right)
            ),
            item.pos
        ) { onChange(item.copy(pos = it)) }
        Spacer(Modifier.height(8.dp))
    }
}
