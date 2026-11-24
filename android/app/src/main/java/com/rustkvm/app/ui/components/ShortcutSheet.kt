package com.rustkvm.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rustkvm.client.hid.Shortcuts

/**
 * 快捷键面板：五组预设 + 自定义 + 开机连按。只管展示和收集操作，发送和连按在 ControlScreen 里。
 * [onAdd] 返回错误文案，null 表示加上了。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShortcutSheet(
    custom: List<Shortcuts.Shortcut>,
    repeatEnabled: Boolean,
    onRepeatEnabledChange: (Boolean) -> Unit,
    repeating: Boolean,
    onStopRepeat: () -> Unit,
    onRun: (Shortcuts.Shortcut) -> Unit,
    onAdd: (label: String, combo: String) -> String?,
    onDelete: (index: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("开机连按", modifier = Modifier.weight(1f))
                if (repeating) {
                    TextButton(onClick = onStopRepeat) {
                        Text("停止连按", color = MaterialTheme.colorScheme.error)
                    }
                }
                Switch(checked = repeatEnabled, onCheckedChange = onRepeatEnabledChange)
            }
            Hint(
                "打开后点按键会每 ${Shortcuts.REPEAT_INTERVAL_MS / 1000.0} 秒按一次，" +
                    "最长 ${Shortcuts.REPEAT_MAX_MS / 1000} 秒。先打开连按再给被控端开机，就不会错过 BIOS 热键",
            )

            Shortcuts.PRESETS.forEach { group ->
                GroupTitle(group.title)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.items.forEach { item -> ShortcutButton(item, onClick = { onRun(item) }) }
                }
            }

            if (custom.isNotEmpty()) {
                GroupTitle("自定义")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    custom.forEachIndexed { index, item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ShortcutButton(item, onClick = { onRun(item) })
                            TextButton(onClick = { onDelete(index) }) { Text("删除") }
                        }
                    }
                }
            }

            AddCustomForm(full = custom.size >= Shortcuts.MAX_CUSTOM, onAdd = onAdd)
        }
    }
}

@Composable
private fun AddCustomForm(full: Boolean, onAdd: (String, String) -> String?) {
    var label by remember { mutableStateOf("") }
    var combo by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    GroupTitle("添加自定义")
    OutlinedTextField(
        value = label,
        onValueChange = { label = it },
        label = { Text("名称（可选）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = combo,
            onValueChange = {
                combo = it
                error = null
            },
            label = { Text("如 Ctrl+Alt+F2") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            error = if (full) {
                "最多保存 ${Shortcuts.MAX_CUSTOM} 个自定义快捷键"
            } else {
                onAdd(label, combo)
            }
            if (error == null) {
                label = ""
                combo = ""
            }
        }) {
            Text("添加")
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
    Hint("用 + 连接同时按的键；多步用空格隔开，如「Win+R Enter」。修饰键可写 Ctrl、Shift、Alt、Win，Mac 上写 Cmd、Option。")
}

@Composable
private fun ShortcutButton(item: Shortcuts.Shortcut, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Column {
            Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.combo,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
