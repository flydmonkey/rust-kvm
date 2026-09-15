package com.rustkvm.app.ui

import android.view.KeyEvent as AndroidKeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.focusable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rustkvm.app.ui.input.ch9329TouchInput
import com.rustkvm.app.ui.components.AudioToggle
import com.rustkvm.client.audio.PcmPlayer
import com.rustkvm.client.hid.ControlBusy
import com.rustkvm.client.hid.SerialControl
import com.rustkvm.client.net.CertDecision
import com.rustkvm.client.net.KvmHttpClient
import com.rustkvm.client.net.KvmSession
import com.rustkvm.client.video.VideoPipeline
import com.rustkvm.protocol.CertTrustStore
import com.rustkvm.protocol.KvmConfig
import com.rustkvm.protocol.parseAudioError
import com.rustkvm.protocol.parseAudioText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
fun ControlScreen(
    baseUrl: String,
    trustStore: CertTrustStore,
) {
    var surface by remember { mutableStateOf<Surface?>(null) }
    var state by remember(baseUrl) { mutableStateOf<ControlState>(ControlState.Connecting) }
    var muted by remember(baseUrl) { mutableStateOf(false) }
    var audioError by remember(baseUrl) { mutableStateOf<String?>(null) }
    var config by remember(baseUrl) { mutableStateOf<KvmConfig?>(null) }
    var serialControl by remember(baseUrl) { mutableStateOf<SerialControl<KvmSession>?>(null) }
    var serialBusy by remember(baseUrl) { mutableStateOf<ControlBusy?>(null) }
    var takeoverSerial by remember(baseUrl) { mutableStateOf(false) }
    val pcmPlayer = remember(baseUrl) { PcmPlayer() }
    val focusRequester = remember(baseUrl) { FocusRequester() }
    SideEffect { pcmPlayer.muted = muted }
    DisposableEffect(pcmPlayer) {
        onDispose { pcmPlayer.close() }
    }

    LaunchedEffect(baseUrl, surface, pcmPlayer, takeoverSerial) {
        val targetSurface = surface ?: return@LaunchedEffect
        state = ControlState.Connecting
        audioError = null
        val client = KvmHttpClient(
            baseUrl = baseUrl,
            trustStore = trustStore,
            onCertDecision = { decision -> rejectUnexpectedTrustDecision(decision) },
        )
        var control: SerialControl<KvmSession>? = null
        try {
            val loadedConfig = client.getConfig()
            config = loadedConfig
            val activeControl = SerialControl(
                sessionFactory = client::newSession,
                settings = loadedConfig.serial,
                onBusy = { serialBusy = it },
            )
            control = activeControl
            serialControl = activeControl
            val session = activeControl.open(takeover = takeoverSerial)
            serialBusy = null
            state = ControlState.Streaming
            focusRequester.requestFocus()
            coroutineScope {
                launch {
                    VideoPipeline(session, targetSurface).run()
                }
                launch {
                    try {
                        session.audioText.collect { text ->
                            val header = parseAudioText(text)
                            if (header != null) {
                                pcmPlayer.onFormat(header)
                                audioError = null
                            } else {
                                parseAudioError(text)?.let { audioError = it }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        audioError = error.message ?: "音频播放不可用"
                    }
                }
                launch(Dispatchers.IO) {
                    try {
                        session.audioBinary.collect(pcmPlayer::write)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        audioError = error.message ?: "音频流已断开"
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (busy: ControlBusy) {
            serialBusy = busy
        } catch (error: Exception) {
            state = ControlState.Failed(error.message ?: "视频连接失败")
        } finally {
            if (serialControl === control) serialControl = null
            control?.close()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val currentConfig = config
        VideoSurface(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    val key = event.nativeKeyEvent.toCh9329Key() ?: return@onPreviewKeyEvent false
                    runCatching {
                        when (event.nativeKeyEvent.action) {
                            AndroidKeyEvent.ACTION_DOWN -> serialControl?.keydown(key)
                            AndroidKeyEvent.ACTION_UP -> serialControl?.keyup(key)
                        }
                    }.onFailure { state = ControlState.Failed(it.message ?: "键盘输入发送失败") }
                    true
                }
                .focusable()
                .then(
                    if (currentConfig == null) {
                        Modifier
                    } else {
                        Modifier.ch9329TouchInput(
                            videoWidth = currentConfig.video.width,
                            videoHeight = currentConfig.video.height,
                            absoluteMouse = currentConfig.serial.mouseMode != "relative",
                            onAbsolute = { x, y, buttons ->
                                runCatching {
                                    serialControl?.mouseMoveAbsolute(x, y, buttons)
                                }.onFailure {
                                    state = ControlState.Failed(it.message ?: "触摸输入发送失败")
                                }
                            },
                            onRelative = { dx, dy, buttons ->
                                runCatching {
                                    serialControl?.mouseMoveRelative(dx, dy, buttons)
                                }.onFailure {
                                    state = ControlState.Failed(it.message ?: "触摸输入发送失败")
                                }
                            },
                        )
                    },
                ),
            onSurface = { surface = it },
        )
        when (val current = state) {
            ControlState.Connecting -> CircularProgressIndicator()
            is ControlState.Failed -> Text(
                text = "连接失败：${current.message}",
                color = MaterialTheme.colorScheme.error,
            )
            ControlState.Streaming -> Unit
        }
        AudioToggle(
            muted = muted,
            onMutedChange = { muted = it },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        ) {
            TextButton(onClick = {
                runCatching { serialControl?.releaseAllKeys() }
                    .onFailure { state = ControlState.Failed(it.message ?: "按键释放失败") }
            }) {
                Text("释放按键")
            }
            TextButton(onClick = {
                runCatching { serialControl?.ctrlAltDelete() }
                    .onFailure { state = ControlState.Failed(it.message ?: "Ctrl+Alt+Del 发送失败") }
            }) {
                Text("Ctrl+Alt+Del")
            }
        }
        audioError?.let { message ->
            Text(
                text = "音频不可用：$message",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }
    }

    serialBusy?.let { busy ->
        AlertDialog(
            onDismissRequest = {
                serialBusy = null
                state = ControlState.Failed("串口被其他客户端占用")
            },
            title = { Text("串口已被占用") },
            text = { Text(busy.message ?: "另一客户端正在控制键盘和鼠标。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        serialBusy = null
                        takeoverSerial = true
                    },
                ) {
                    Text("接管")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        serialBusy = null
                        state = ControlState.Failed("串口被其他客户端占用")
                    },
                ) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun VideoSurface(
    modifier: Modifier,
    onSurface: (Surface?) -> Unit,
) {
    val currentOnSurface by rememberUpdatedState(onSurface)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).also { view ->
                view.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        currentOnSurface(holder.surface)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        currentOnSurface(holder.surface)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        currentOnSurface(null)
                    }
                })
            }
        },
    )
}

private fun rejectUnexpectedTrustDecision(decision: CertDecision): Boolean =
    when (decision) {
        CertDecision.Trusted -> true
        is CertDecision.NeedsUserTrust -> throw IOException(
            "服务器证书不在信任记录中，请返回连接页重新确认",
        )
        is CertDecision.Mismatch -> throw IOException(
            "服务器证书已变更，请返回连接页重新确认",
        )
    }

private fun AndroidKeyEvent.toCh9329Key(): String? = when (keyCode) {
    in AndroidKeyEvent.KEYCODE_A..AndroidKeyEvent.KEYCODE_Z ->
        "Key${('A'.code + keyCode - AndroidKeyEvent.KEYCODE_A).toChar()}"
    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 ->
        "Digit${keyCode - AndroidKeyEvent.KEYCODE_0}"
    in AndroidKeyEvent.KEYCODE_F1..AndroidKeyEvent.KEYCODE_F12 ->
        "F${keyCode - AndroidKeyEvent.KEYCODE_F1 + 1}"
    AndroidKeyEvent.KEYCODE_SHIFT_LEFT -> "ShiftLeft"
    AndroidKeyEvent.KEYCODE_SHIFT_RIGHT -> "ShiftRight"
    AndroidKeyEvent.KEYCODE_CTRL_LEFT -> "ControlLeft"
    AndroidKeyEvent.KEYCODE_CTRL_RIGHT -> "ControlRight"
    AndroidKeyEvent.KEYCODE_ALT_LEFT -> "AltLeft"
    AndroidKeyEvent.KEYCODE_ALT_RIGHT -> "AltRight"
    AndroidKeyEvent.KEYCODE_META_LEFT -> "MetaLeft"
    AndroidKeyEvent.KEYCODE_META_RIGHT -> "MetaRight"
    AndroidKeyEvent.KEYCODE_DEL -> "Backspace"
    AndroidKeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
    AndroidKeyEvent.KEYCODE_TAB -> "Tab"
    AndroidKeyEvent.KEYCODE_ENTER -> "Enter"
    AndroidKeyEvent.KEYCODE_ESCAPE -> "Escape"
    AndroidKeyEvent.KEYCODE_SPACE -> "Space"
    AndroidKeyEvent.KEYCODE_PAGE_UP -> "PageUp"
    AndroidKeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
    AndroidKeyEvent.KEYCODE_MOVE_END -> "End"
    AndroidKeyEvent.KEYCODE_MOVE_HOME -> "Home"
    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
    AndroidKeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
    AndroidKeyEvent.KEYCODE_INSERT -> "Insert"
    AndroidKeyEvent.KEYCODE_CAPS_LOCK -> "CapsLock"
    AndroidKeyEvent.KEYCODE_SEMICOLON -> "Semicolon"
    AndroidKeyEvent.KEYCODE_EQUALS -> "Equal"
    AndroidKeyEvent.KEYCODE_COMMA -> "Comma"
    AndroidKeyEvent.KEYCODE_MINUS -> "Minus"
    AndroidKeyEvent.KEYCODE_PERIOD -> "Period"
    AndroidKeyEvent.KEYCODE_SLASH -> "Slash"
    AndroidKeyEvent.KEYCODE_GRAVE -> "Backquote"
    AndroidKeyEvent.KEYCODE_LEFT_BRACKET -> "BracketLeft"
    AndroidKeyEvent.KEYCODE_BACKSLASH -> "Backslash"
    AndroidKeyEvent.KEYCODE_RIGHT_BRACKET -> "BracketRight"
    AndroidKeyEvent.KEYCODE_APOSTROPHE -> "Quote"
    else -> null
}

private sealed interface ControlState {
    data object Connecting : ControlState
    data object Streaming : ControlState
    data class Failed(val message: String) : ControlState
}
