package com.rustkvm.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val MODIFIER_KEYS = listOf(
    "Ctrl" to "ControlLeft",
    "Alt" to "AltLeft",
    "Shift" to "ShiftLeft",
    "Win" to "MetaLeft",
)

private val ACTION_KEYS = listOf(
    "Esc" to "Escape",
    "Tab" to "Tab",
    "←" to "ArrowLeft",
    "↑" to "ArrowUp",
    "↓" to "ArrowDown",
    "→" to "ArrowRight",
    "Home" to "Home",
    "End" to "End",
    "PgUp" to "PageUp",
    "PgDn" to "PageDown",
    "Del" to "Delete",
) + (1..12).map { "F$it" to "F$it" }

/** 软键盘上没有的键。修饰键是粘滞的，语义见 [com.rustkvm.app.ui.input.onModifierTap]。 */
@Composable
fun ExtraKeysBar(
    latchedModifiers: Set<String>,
    onModifier: (String) -> Unit,
    onKey: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MODIFIER_KEYS.forEach { (label, key) ->
            FilterChip(
                selected = key in latchedModifiers,
                onClick = { onModifier(key) },
                label = { Text(label) },
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
        ACTION_KEYS.forEach { (label, key) ->
            TextButton(onClick = { onKey(key) }) {
                Text(label)
            }
        }
    }
}
