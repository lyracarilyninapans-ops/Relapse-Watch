package com.example.relapse_watch.presentation.screens

import android.media.AudioAttributes
import android.media.MediaPlayer
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.CircularProgressIndicator
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun ReminderScreen(
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
        Log.d(
            TAG,
            "Playback mode=$mode image=${imageUri != null} audio=${audioUri != null} video=${videoUri != null}"
        )
    }

    if (mode == PlaybackMode.AudioOnly || mode == PlaybackMode.PhotoWithAudio) {
        AudioPlaybackEffect(
            audioUri = audioUri,
            onReady = { durationMs ->
                fallbackDurationMs = durationMs
                playbackReady = true
            },
            onCompleted = onPlaybackFinished,
            onError = {
                mediaLoadError = true
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
                Log.e(TAG, "Video ExoPlayer stream timed out for uri=$videoUri")
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
                    Log.e(TAG, "ExoPlayer error for uri=$videoUri", error)
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

    // Handle generic timeout/duration for errors or text/photo only
    LaunchedEffect(playbackReady, mediaLoadError, mode) {
        if (playbackReady || mediaLoadError || mode == PlaybackMode.PhotoOnly || mode == PlaybackMode.TextOnly) {
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

    LaunchedEffect(audioUri) {
        delay(15000L) // 15s timeout
        if (!isReady) {
            Log.e(TAG, "Audio stream timed out for uri=$audioUri")
            onError()
        }
    }

    DisposableEffect(audioUri) {
        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mediaPlayer.setDataSource(audioUri.toString())
            mediaPlayer.setOnPreparedListener { player ->
                isReady = true
                val rawDuration = player.duration.toLong()
                onReady(if (rawDuration > 0) rawDuration else DEFAULT_DISPLAY_DURATION_MS)
                player.start()
            }
            mediaPlayer.setOnCompletionListener {
                onCompleted()
            }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                onError()
                true
            }
            mediaPlayer.prepareAsync()
        } catch (_: Exception) {
            onError()
        }

        onDispose {
            runCatching {
                if (mediaPlayer.isPlaying) mediaPlayer.stop()
                mediaPlayer.release()
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
private const val TAG = "ReminderScreen"
