package com.rustkvm.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rustkvm.app.ui.components.ExtraKeysBar
import com.rustkvm.app.ui.input.KEYBOARD_SENTINEL
import com.rustkvm.app.ui.input.KeyboardEdit
import com.rustkvm.app.ui.input.NormalizedTouch
import com.rustkvm.app.ui.input.PointerSink
import com.rustkvm.app.ui.input.ch9329TouchInput
import com.rustkvm.app.ui.input.diffKeyboardInput
import com.rustkvm.app.ui.input.nextKeyboardSentinel
import com.rustkvm.app.ui.input.onModifierTap
import com.rustkvm.app.ui.components.AudioToggle
import com.rustkvm.client.audio.PcmPlayer
import com.rustkvm.client.audio.trimAudioBacklog
import com.rustkvm.client.hid.ControlBusy
import com.rustkvm.client.hid.SerialControl
import com.rustkvm.client.hid.SerialControlFailure
import com.rustkvm.client.net.CertDecision
import com.rustkvm.client.net.KvmHttpClient
import com.rustkvm.client.net.KvmSession
import com.rustkvm.client.net.ReconnectPolicy
import com.rustkvm.client.net.StreamMessage
import com.rustkvm.client.net.receiveBatch
import com.rustkvm.client.video.VideoPipeline
import com.rustkvm.protocol.CertTrustStore
import com.rustkvm.protocol.KvmConfig
import com.rustkvm.protocol.parseAudioError
import com.rustkvm.protocol.parseAudioText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlScreen(
    baseUrl: String,
    trustStore: CertTrustStore,
    onDisconnect: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    var surface by remember { mutableStateOf<Surface?>(null) }
    var state by remember(baseUrl) { mutableStateOf<ControlState>(ControlState.Connecting) }
    var muted by remember(baseUrl) { mutableStateOf(false) }
    var audioError by remember(baseUrl) { mutableStateOf<String?>(null) }
    var config by remember(baseUrl) { mutableStateOf<KvmConfig?>(null) }
    var serialControl by remember(baseUrl) { mutableStateOf<SerialControl<KvmSession>?>(null) }
    var serialBusy by remember(baseUrl) { mutableStateOf<ControlBusy?>(null) }
    var requestedTakeover by remember(baseUrl) { mutableStateOf(false) }
    var userDisconnected by remember(baseUrl) { mutableStateOf(false) }
    var absoluteMouse by remember(baseUrl) { mutableStateOf<Boolean?>(null) }
    var connectionGeneration by remember(baseUrl) { mutableStateOf(0) }
    var videoSize by remember(baseUrl) { mutableStateOf<Pair<Int, Int>?>(null) }
    val pcmPlayer = remember(baseUrl) { PcmPlayer() }
    val focusRequester = remember(baseUrl) { FocusRequester() }
    val keyboardFocus = remember(baseUrl) { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var keyboardSentinel by remember(baseUrl) { mutableStateOf(KEYBOARD_SENTINEL) }
    var keyboardField by remember(baseUrl) {
        mutableStateOf(TextFieldValue(KEYBOARD_SENTINEL, TextRange(KEYBOARD_SENTINEL.length)))
    }
    val keyInputs = remember(baseUrl) { Channel<KeyInput>(Channel.UNLIMITED) }
    var latchedModifiers by remember(baseUrl) { mutableStateOf(emptySet<String>()) }
    var videoNotice by remember(baseUrl) { mutableStateOf<String?>(null) }
    val imeVisible = WindowInsets.isImeVisible
    val clipboard = LocalClipboardManager.current
    val uiScope = rememberCoroutineScope()
    SideEffect { pcmPlayer.muted = muted }
    DisposableEffect(pcmPlayer) {
        onDispose { pcmPlayer.close() }
    }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        val insetsController = view.context.findActivity()?.window?.let { window ->
            WindowCompat.getInsetsController(window, view)
        }
        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            view.keepScreenOn = false
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 收起软键盘时把锁定的修饰键也松开，免得下次打字莫名带上 Ctrl
    LaunchedEffect(imeVisible) {
        if (!imeVisible) latchedModifiers = emptySet()
    }

    val pointerSink = remember(baseUrl) {
        object : PointerSink {
            override fun absolute(point: NormalizedTouch, buttons: Int) =
                send { it.mouseMoveAbsolute(point.x, point.y, buttons) }

            override fun relative(dx: Int, dy: Int, buttons: Int) =
                send { it.mouseMoveRelative(dx, dy, buttons) }

            override fun scroll(notches: Int) = send { it.mouseScroll(notches) }

            private fun send(action: (SerialControl<KvmSession>) -> Unit) {
                val control = serialControl ?: return
                runCatching { action(control) }
                    .onFailure { state = ControlState.Failed(it.message ?: "触摸输入发送失败") }
            }
        }
    }

    // 直接离开页面：连接循环随页面退出被取消，串口在它的 finally 里关闭。连接失败后循环
    // 已经结束，不能指望它来调用 onDisconnect
    fun disconnect() {
        if (userDisconnected) return
        userDisconnected = true
        runCatching { serialControl?.releaseAllKeys() }
        onDisconnect()
    }

    val backPress = remember(baseUrl) { DoubleBackPress() }
    var backNotice by remember(baseUrl) { mutableStateOf<String?>(null) }
    BackHandler {
        if (backPress.press(SystemClock.uptimeMillis())) {
            disconnect()
        } else {
            backNotice = "再按一次返回断开连接"
        }
    }
    LaunchedEffect(backNotice) {
        if (backNotice != null) {
            delay(DoubleBackPress.DOUBLE_BACK_WINDOW_MS)
            backNotice = null
        }
    }

    // 软键盘和功能键栏的按键必须按顺序逐个敲完：并发敲的话按键会交错
    LaunchedEffect(keyInputs) {
        for (input in keyInputs) {
            val control = serialControl ?: continue
            try {
                when (input) {
                    is KeyInput.Tap -> control.tapKey(input.key, input.modifiers)
                    is KeyInput.Typed -> {
                        // 锁定的修饰键只作用于第一个键
                        var modifiers = input.modifiers
                        repeat(input.edit.deleted) {
                            control.tapKey("Backspace", modifiers)
                            modifiers = emptySet()
                        }
                        val inserted = input.edit.inserted
                        if (inserted.isNotEmpty()) {
                            control.typeText(inserted.take(1), modifiers)
                            if (inserted.length > 1) control.typeText(inserted.drop(1), emptySet())
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state = ControlState.Failed(error.message ?: "键盘输入发送失败")
            }
        }
    }

    LaunchedEffect(baseUrl, surface, pcmPlayer, connectionGeneration) {
        val targetSurface = surface ?: return@LaunchedEffect
        val client = KvmHttpClient(
            baseUrl = baseUrl,
            trustStore = trustStore,
            onCertDecision = { decision -> rejectUnexpectedTrustDecision(decision) },
        )
        val reconnectPolicy = ReconnectPolicy()
        var takeover = requestedTakeover
        requestedTakeover = false
        var hasStreamed = false
        while (!userDisconnected) {
            var control: SerialControl<KvmSession>? = null
            try {
                state = if (hasStreamed) ControlState.Reconnecting else ControlState.Connecting
                audioError = null
                val loadedConfig = client.getConfig()
                config = loadedConfig
                absoluteMouse = loadedConfig.serial.mouseMode != "relative"
                val activeControl = SerialControl(
                    sessionFactory = client::newSession,
                    settings = loadedConfig.serial,
                    onBusy = { serialBusy = it },
                    onInputFailure = { error ->
                        uiScope.launch {
                            state = ControlState.Failed(error.message ?: "串口输入发送失败")
                        }
                    },
                )
                control = activeControl
                serialControl = activeControl
                absoluteMouse?.let { activeControl.setAbsoluteMouse(it) }
                val session = activeControl.open(takeover = takeover)
                takeover = false
                reconnectPolicy.reset()
                serialBusy = null
                state = ControlState.Streaming
                hasStreamed = true
                focusRequester.requestFocus()
                coroutineScope {
                    launch {
                        VideoPipeline(
                            session = session,
                            surface = targetSurface,
                            onVideoSize = { width, height -> videoSize = width to height },
                            onCodec = { _, fallbackReason ->
                                videoNotice = fallbackReason?.let { "H.264 不可用，已退回 MJPEG：$it" }
                            },
                        ).run()
                    }
                    launch(Dispatchers.IO) {
                        try {
                            while (true) {
                                val batch = session.audioMessages.receiveBatch() ?: break
                                for (message in trimAudioBacklog(batch)) {
                                    when (message) {
                                        is StreamMessage.Text -> {
                                            val header = parseAudioText(message.text)
                                            if (header != null) {
                                                pcmPlayer.onFormat(header)
                                                audioError = null
                                            } else {
                                                parseAudioError(message.text)?.let { audioError = it }
                                            }
                                        }

                                        is StreamMessage.Binary -> pcmPlayer.write(message.data)
                                    }
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            audioError = error.message ?: "音频流已断开"
                        }
                    }
                    launch {
                        activeControl.monitorDownlink()
                    }
                    launch {
                        throw (session.awaitDisconnect() ?: IOException("会话已断开"))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (busy: ControlBusy) {
                serialBusy = busy
                state = ControlState.Failed("串口被其他客户端占用")
                return@LaunchedEffect
            } catch (error: SerialControlFailure) {
                state = ControlState.Failed(error.message ?: "串口连接失败")
                return@LaunchedEffect
            } catch (error: Exception) {
                if (userDisconnected) break
                if (!shouldRetryControlConnection(hasStreamed, error)) {
                    state = ControlState.Failed(error.message ?: "连接失败")
                    return@LaunchedEffect
                }
                takeover = false
                state = ControlState.Reconnecting
                delay(reconnectPolicy.nextDelayMs())
                if (userDisconnected) break
            } finally {
                if (serialControl === control) serialControl = null
                control?.close()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .displayCutoutPadding()
            .imePadding()
            // 挂在外层而不是画面上：软键盘那个隐藏输入框获得焦点时，外接键盘的按键
            // 也要先被这里截走，否则会被当成文字输入、丢掉 Ctrl 之类的组合键
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent.toCh9329Key() ?: return@onPreviewKeyEvent false
                runCatching {
                    when (event.nativeKeyEvent.action) {
                        AndroidKeyEvent.ACTION_DOWN -> serialControl?.keydown(key)
                        AndroidKeyEvent.ACTION_UP -> serialControl?.keyup(key)
                    }
                }.onFailure { state = ControlState.Failed(it.message ?: "键盘输入发送失败") }
                true
            },
        contentAlignment = Alignment.Center,
    ) {
        val currentConfig = config
        val useAbsoluteMouse = absoluteMouse ?: (currentConfig?.serial?.mouseMode != "relative")
        // 画面区域按实际画面比例布局，而不是铺满后再留黑边：H.264 由 MediaCodec 直接
        // 铺满 Surface、不保持比例，铺满的话画面被拉伸，触摸坐标也就对不上了
        val (videoWidth, videoHeight) = videoSize
            ?: currentConfig?.let { it.video.width to it.video.height }
            ?: (DEFAULT_VIDEO_WIDTH to DEFAULT_VIDEO_HEIGHT)
        VideoSurface(
            modifier = Modifier
                .aspectRatio(videoWidth.toFloat() / videoHeight)
                .focusRequester(focusRequester)
                .focusable()
                .ch9329TouchInput(
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    absoluteMouse = useAbsoluteMouse,
                    sink = pointerSink,
                ),
            onSurface = { surface = it },
        )
        BasicTextField(
            value = keyboardField,
            onValueChange = { next ->
                val edit = diffKeyboardInput(next.text, keyboardSentinel)
                if (!edit.isEmpty) {
                    keyInputs.trySend(KeyInput.Typed(edit, latchedModifiers))
                    latchedModifiers = emptySet()
                }
                keyboardSentinel = nextKeyboardSentinel(keyboardSentinel)
                keyboardField = TextFieldValue(keyboardSentinel, TextRange(keyboardSentinel.length))
            },
            // 密码类型让输入法不做联想、自动更正和拼写组合，否则提交的文字会被反复改写
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(1.dp)
                .alpha(0f)
                .focusRequester(keyboardFocus),
        )
        when (val current = state) {
            ControlState.Connecting -> CircularProgressIndicator()
            ControlState.Reconnecting -> Text(
                text = "重连中…",
                color = MaterialTheme.colorScheme.onSurface,
            )
            is ControlState.Failed -> Text(
                text = "连接失败：${current.message}",
                color = MaterialTheme.colorScheme.error,
            )
            ControlState.Streaming -> Unit
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            AudioToggle(
                muted = muted,
                onMutedChange = { muted = it },
            )
            TextButton(onClick = {
                runCatching { keyboardFocus.requestFocus() }
                keyboardController?.show()
            }) {
                Text("键盘")
            }
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
            TextButton(onClick = {
                val text = clipboard.getText()?.text.orEmpty()
                if (text.isNotEmpty()) {
                    uiScope.launch {
                        runCatching { serialControl?.pasteText(text) }
                            .onFailure { state = ControlState.Failed(it.message ?: "粘贴失败") }
                    }
                }
            }) {
                Text("粘贴")
            }
            TextButton(onClick = {
                val next = !useAbsoluteMouse
                absoluteMouse = next
                runCatching { serialControl?.setAbsoluteMouse(next) }
            }) {
                Text(if (useAbsoluteMouse) "鼠标：绝对" else "鼠标：相对")
            }
            TextButton(onClick = ::disconnect) {
                Text("断开")
            }
            TextButton(onClick = onOpenSettings) {
                Text("设置")
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            backNotice?.let { message ->
                Text(
                    text = message,
                    color = Color.White,
                    modifier = Modifier
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            listOfNotNull(videoNotice, audioError?.let { "音频不可用：$it" }).forEach { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (imeVisible) {
                ExtraKeysBar(
                    latchedModifiers = latchedModifiers,
                    onModifier = { modifier ->
                        val result = onModifierTap(latchedModifiers, modifier)
                        latchedModifiers = result.latched
                        result.sendKey?.let { keyInputs.trySend(KeyInput.Tap(it, result.sendModifiers)) }
                    },
                    onKey = { key ->
                        keyInputs.trySend(KeyInput.Tap(key, latchedModifiers))
                        latchedModifiers = emptySet()
                    },
                )
            }
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
                        requestedTakeover = true
                        connectionGeneration += 1
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

internal class TerminalControlConnectionException(message: String) : IOException(message)

internal fun shouldRetryControlConnection(
    hasStreamed: Boolean,
    error: Exception,
): Boolean = hasStreamed && error !is TerminalControlConnectionException

internal fun rejectUnexpectedTrustDecision(decision: CertDecision): Boolean =
    when (decision) {
        CertDecision.Trusted -> true
        is CertDecision.NeedsUserTrust -> throw TerminalControlConnectionException(
            "服务器证书不在信任记录中，请返回连接页重新确认",
        )
        is CertDecision.Mismatch -> throw TerminalControlConnectionException(
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

private sealed interface KeyInput {
    data class Typed(val edit: KeyboardEdit, val modifiers: Set<String>) : KeyInput
    data class Tap(val key: String, val modifiers: Set<String>) : KeyInput
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val DEFAULT_VIDEO_WIDTH = 1920
private const val DEFAULT_VIDEO_HEIGHT = 1080

private sealed interface ControlState {
    data object Connecting : ControlState
    data object Reconnecting : ControlState
    data object Streaming : ControlState
    data class Failed(val message: String) : ControlState
}
