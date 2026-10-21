package com.rustkvm.app.ui.components

import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun AudioToggle(
    muted: Boolean,
    onMutedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = { onMutedChange(!muted) },
        modifier = modifier,
    ) {
        Text(if (muted) "开启声音" else "静音")
    }
}
