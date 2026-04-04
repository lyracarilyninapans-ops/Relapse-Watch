package com.example.relapse_watch.presentation.screens

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.AudioAttributes as ExoAudioAttributes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.CircularProgressIndicator
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun ReminderScreen(
    correlationKey: String? = null,
    title: String,
    body: String,
    imageUri: Uri? = null,
    audioUri: Uri? = null,
    videoUri: Uri? = null,
    onPlaybackFinished: () -> Unit
) {
    val context = LocalContext.current

    val mode = remember(imageUri, audioUri, videoUri) {
        when {
            videoUri != null && imageUri == null && audioUri == null -> PlaybackMode.VideoOnly
            imageUri != null && audioUri != null -> PlaybackMode.PhotoWithAudio
            imageUri != null -> PlaybackMode.PhotoOnly
            audioUri != null -> PlaybackMode.AudioOnly
            videoUri != null -> PlaybackMode.VideoOnly
            else -> PlaybackMode.TextOnly
        }
    }

    var fallbackDurationMs by remember(mode) {
        mutableLongStateOf(DEFAULT_DISPLAY_DURATION_MS)
    }
    var playbackReady by remember(mode) { mutableStateOf(false) }
    var mediaLoadError by remember(mode) { mutableStateOf(false) }
    var imageLoadFailed by remember(mode) { mutableStateOf(false) }

    LaunchedEffect(mode, imageUri, audioUri, videoUri) {
        val key = correlationKey ?: "unknown"
        Log.d(
            TAG,
            "[R_TRACE][REMINDER_PLAYBACK] key=$key mode=$mode image=${imageUri != null} audio=${audioUri != null} video=${videoUri != null}"
        )
    }

    if (mode == PlaybackMode.AudioOnly || mode == PlaybackMode.PhotoWithAudio) {
        AudioPlaybackEffect(
            context = context,
            correlationKey = correlationKey,
            audioUri = audioUri,
            onReady = { durationMs ->
                fallbackDurationMs = durationMs
                playbackReady = true
            },
            onCompleted = onPlaybackFinished,
            onError = {
                if (mode == PlaybackMode.PhotoWithAudio && imageUri != null) {
                    playbackReady = true
                } else {
                    mediaLoadError = true
                }
            }
        )
    }

    val exoPlayer = remember {
        if (mode == PlaybackMode.VideoOnly) ExoPlayer.Builder(context).build() else null
    }

    if (mode == PlaybackMode.VideoOnly && videoUri != null && exoPlayer != null) {
        var isReady by remember(videoUri) { mutableStateOf(false) }

        LaunchedEffect(videoUri) {
            delay(15000L) // 15s timeout
            if (!isReady) {
                Log.e(TAG, "[R_TRACE][REMINDER_PLAYBACK][VIDEO_TIMEOUT] key=${correlationKey ?: "unknown"} uri=$videoUri")
                mediaLoadError = true
            }
        }

        DisposableEffect(videoUri) {
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && !isReady) {
                        isReady = true
                        val dur = exoPlayer.duration
                        fallbackDurationMs = if (dur > 0) dur else DEFAULT_DISPLAY_DURATION_MS
                        playbackReady = true
                    } else if (playbackState == Player.STATE_ENDED) {
                        onPlaybackFinished()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "[R_TRACE][REMINDER_PLAYBACK][VIDEO_ERROR] key=${correlationKey ?: "unknown"} uri=$videoUri", error)
                    mediaLoadError = true
                }
            }
            exoPlayer.addListener(listener)
            exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true

            onDispose {
                exoPlayer.removeListener(listener)
                exoPlayer.release()
            }
        }
    }

    val shouldUseAutoFinishTimer = remember(mode, mediaLoadError) {
        mediaLoadError || mode == PlaybackMode.PhotoOnly || mode == PlaybackMode.TextOnly
    }

    // Auto-finish timer is only for non-player flows or explicit media error states.
    LaunchedEffect(playbackReady, mediaLoadError, mode) {
        if (shouldUseAutoFinishTimer) {
            delay(fallbackDurationMs)
            onPlaybackFinished()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (mediaLoadError) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Network too slow.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp, start = 12.dp, end = 12.dp)
                )
                if (body.isNotBlank()) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp, start = 12.dp, end = 12.dp)
                    )
                }
            }
        } else if (!playbackReady && (mode == PlaybackMode.AudioOnly || mode == PlaybackMode.PhotoWithAudio || mode == PlaybackMode.VideoOnly)) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Text(
                    text = "Loading Media...",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {
            when (mode) {
                PlaybackMode.PhotoOnly,
                PlaybackMode.PhotoWithAudio -> {
                    if (imageUri != null && !imageLoadFailed) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(imageUri.toString())
                                .build(),
                            contentDescription = title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            onState = { state ->
                                if (state is AsyncImagePainter.State.Error) {
                                    Log.e(TAG, "Image load failed for uri=$imageUri", state.result.throwable)
                                    imageLoadFailed = true
                                }
                            }
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    }
                }

                PlaybackMode.VideoOnly -> {
                    if (exoPlayer != null && !mediaLoadError) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        useController = false
                                        player = exoPlayer
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        // Fallback text if something failed at UI level but mediaLoadError wasn't caught yet
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    }
                }

                PlaybackMode.AudioOnly,
                PlaybackMode.TextOnly -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )

                        if (body.isNotBlank()) {
                            Text(
                                text = body,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 12.dp, vertical = 16.dp)
                            )
                        }
                    }
                }
            }
        }

        if (mode == PlaybackMode.PhotoOnly || mode == PlaybackMode.PhotoWithAudio || mode == PlaybackMode.VideoOnly) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .fillMaxWidth()
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun AudioPlaybackEffect(
    context: android.content.Context,
    correlationKey: String?,
    audioUri: Uri?,
    onReady: (Long) -> Unit,
    onCompleted: () -> Unit,
    onError: () -> Unit
) {
    if (audioUri == null) {
        onError()
        return
    }

    var isReady by remember(audioUri) { mutableStateOf(false) }
    val terminalCallbackDelivered = remember(audioUri) { AtomicBoolean(false) }

    LaunchedEffect(audioUri) {
        delay(15000L) // 15s timeout
        if (!isReady && terminalCallbackDelivered.compareAndSet(false, true)) {
            Log.e(TAG, "[R_TRACE][REMINDER_PLAYBACK][AUDIO_TIMEOUT] key=${correlationKey ?: "unknown"} uri=$audioUri")
            onError()
        }
    }

    LaunchedEffect(audioUri, isReady) {
        if (!isReady) return@LaunchedEffect
        delay(MAX_AUDIO_PLAYBACK_MS)
        if (terminalCallbackDelivered.compareAndSet(false, true)) {
            Log.d(TAG, "[R_TRACE][REMINDER_PLAYBACK][AUDIO_MAX_DURATION] key=${correlationKey ?: "unknown"} maxMs=$MAX_AUDIO_PLAYBACK_MS")
            onCompleted()
        }
    }

    DisposableEffect(audioUri) {
        val exoPlayer = ExoPlayer.Builder(context).build()
        val released = AtomicBoolean(false)

        fun safeSignalError() {
            if (released.get()) return
            if (terminalCallbackDelivered.compareAndSet(false, true)) {
                onError()
            }
        }

        fun safeSignalCompleted() {
            if (released.get()) return
            if (terminalCallbackDelivered.compareAndSet(false, true)) {
                onCompleted()
            }
        }

        try {
            val audioAttributes = ExoAudioAttributes.Builder()
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .build()

            val focusSetup = runCatching {
                exoPlayer.setAudioAttributes(audioAttributes, true)
            }
            if (focusSetup.isFailure) {
                Log.w(TAG, "Media3 audio focus setup failed, retrying without audio focus", focusSetup.exceptionOrNull())
                runCatching {
                    exoPlayer.setAudioAttributes(audioAttributes, false)
                }.onFailure { fallbackError ->
                    Log.e(TAG, "Media3 audio attribute fallback failed", fallbackError)
                    safeSignalError()
                    return@DisposableEffect onDispose {
                        released.set(true)
                        runCatching { exoPlayer.release() }
                    }
                }
            }

            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (released.get()) return

                    if (playbackState == Player.STATE_READY && !isReady) {
                        isReady = true
                        val rawDuration = exoPlayer.duration
                        val effectiveDuration = if (rawDuration > 0) {
                            rawDuration.coerceAtMost(MAX_AUDIO_PLAYBACK_MS)
                        } else {
                            DEFAULT_DISPLAY_DURATION_MS
                        }
                        onReady(effectiveDuration)
                    } else if (playbackState == Player.STATE_ENDED) {
                        safeSignalCompleted()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "[R_TRACE][REMINDER_PLAYBACK][AUDIO_ERROR] key=${correlationKey ?: "unknown"} uri=$audioUri", error)
                    safeSignalError()
                }
            }

            exoPlayer.addListener(listener)
            exoPlayer.setMediaItem(MediaItem.fromUri(audioUri))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true

            onDispose {
                released.set(true)
                runCatching {
                    exoPlayer.playWhenReady = false
                    exoPlayer.stop()
                    exoPlayer.removeListener(listener)
                    exoPlayer.release()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio setup failed for uri=$audioUri", e)
            safeSignalError()
            onDispose {
                released.set(true)
                runCatching {
                    exoPlayer.release()
                }
            }
        }
    }
}

private enum class PlaybackMode {
    PhotoOnly,
    AudioOnly,
    PhotoWithAudio,
    VideoOnly,
    TextOnly
}

private const val DEFAULT_DISPLAY_DURATION_MS = 10_000L
private const val MAX_AUDIO_PLAYBACK_MS = 120_000L
private const val TAG = "ReminderScreen"
