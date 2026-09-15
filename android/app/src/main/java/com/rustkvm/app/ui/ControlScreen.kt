package com.rustkvm.app.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rustkvm.app.ui.components.AudioToggle
import com.rustkvm.client.audio.PcmPlayer
import com.rustkvm.client.net.CertDecision
import com.rustkvm.client.net.KvmHttpClient
import com.rustkvm.client.video.VideoPipeline
import com.rustkvm.protocol.CertTrustStore
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
    val pcmPlayer = remember(baseUrl) { PcmPlayer() }
    SideEffect { pcmPlayer.muted = muted }
    DisposableEffect(pcmPlayer) {
        onDispose { pcmPlayer.close() }
    }

    LaunchedEffect(baseUrl, surface, pcmPlayer) {
        val targetSurface = surface ?: return@LaunchedEffect
        state = ControlState.Connecting
        audioError = null
        val client = KvmHttpClient(
            baseUrl = baseUrl,
            trustStore = trustStore,
            onCertDecision = { decision -> rejectUnexpectedTrustDecision(decision) },
        )
        val session = client.newSession()
        try {
            session.connect()
            state = ControlState.Streaming
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
        } catch (error: Exception) {
            state = ControlState.Failed(error.message ?: "视频连接失败")
        } finally {
            session.close()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        VideoSurface(
            modifier = Modifier.fillMaxSize(),
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

private sealed interface ControlState {
    data object Connecting : ControlState
    data object Streaming : ControlState
    data class Failed(val message: String) : ControlState
}
