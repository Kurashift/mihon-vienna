package eu.kanade.presentation.audio

import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.data.audio.AudioPlayItem
import eu.kanade.tachiyomi.ui.audio.AudioPlayerController
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The block at the top of the player page: the work's art, with the picture over it when the
 * current track has one.
 *
 * Drawn through a [TextureView] rather than a `SurfaceView` because this block is part of the page
 * and has to behave like it: it is clipped to the same rounded corners as the cover, it slides with
 * the shared-axis page transition, and its height animates when a queue moves between a sound track
 * and a picture. A `SurfaceView` is a separate layer under the window and does none of those.
 *
 * The cover stays underneath and the texture is transparent until the first frame, so the handover
 * needs no crossfade: the picture simply covers the art once there is something to show.
 */
@Composable
internal fun AudioVideoStage(
    controller: AudioPlayerController,
    item: AudioPlayItem?,
    hasVideo: Boolean,
    videoAspectRatio: Float,
    videoReady: Boolean,
    videoUnsupported: Boolean,
    isBuffering: Boolean,
    isScreenVisible: Boolean,
    isFullscreen: Boolean,
    onTap: () -> Unit,
    hudText: String? = null,
    onDoubleTap: (() -> Unit)? = null,
    onFullscreen: (() -> Unit)? = null,
    onSpeedUp: (() -> Unit)? = null,
    onSpeedUpEnd: (() -> Unit)? = null,
    onVerticalDrag: ((leftSide: Boolean, deltaFraction: Float) -> Unit)? = null,
    onVerticalDragEnd: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val videoAspect = videoAspectRatio.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_VIDEO_ASPECT
    val targetAspect = if (hasVideo) videoAspect else 1f
    val animatedAspect by animateFloatAsState(targetAspect, label = "audioVideoStageAspect")

    // No ripple and no press state: this is a picture, and any animation under the finger reads
    // as the video itself reacting. A tap shows the controls, a double tap plays or pauses, and
    // holding speeds the picture up until the finger lifts — all of it invisible.
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentOnSpeedUp by rememberUpdatedState(onSpeedUp)
    val currentOnSpeedUpEnd by rememberUpdatedState(onSpeedUpEnd)
    val tapModifier = Modifier.pointerInput(Unit) {
        detectTapGestures(
            onTap = { currentOnTap() },
            onDoubleTap = { currentOnDoubleTap?.invoke() },
            // A hold speeds the picture up until the finger lifts. Registering onLongPress is
            // also what keeps a hold from being reported as a tap, so the controls do not flicker
            // when the user lets go.
            onLongPress = { currentOnSpeedUp?.invoke() },
            onPress = {
                tryAwaitRelease()
                currentOnSpeedUpEnd?.invoke()
            },
        )
    }

    // Left half adjusts brightness, right half the volume, and a full-height swipe covers the
    // whole range. Vertical drags are consumed here, which is also what cancels the tap above
    // once the finger actually moves.
    val currentOnVerticalDrag by rememberUpdatedState(onVerticalDrag)
    val currentOnVerticalDragEnd by rememberUpdatedState(onVerticalDragEnd)
    val dragModifier = Modifier.pointerInput(Unit) {
        var leftSide = true
        detectVerticalDragGestures(
            onDragStart = { offset -> leftSide = offset.x < size.width / 2f },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                val height = size.height.coerceAtLeast(1)
                currentOnVerticalDrag?.invoke(leftSide, -dragAmount / height)
            },
            onDragEnd = { currentOnVerticalDragEnd?.invoke() },
            onDragCancel = { currentOnVerticalDragEnd?.invoke() },
        )
    }

    val stageModifier = if (isFullscreen) {
        // Tappable here too: the control strip hides itself after a few seconds, and a picture
        // that answers no tap at all leaves the user with nothing to press.
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(tapModifier)
            .then(dragModifier)
    } else {
        modifier
            .fillMaxWidth()
            // Clamped so a portrait recording cannot push the controls off a portrait page; the
            // picture itself keeps its own ratio inside and is letterboxed.
            .aspectRatio(animatedAspect.coerceIn(MIN_STAGE_ASPECT, MAX_STAGE_ASPECT))
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(tapModifier)
            .then(dragModifier)
    }

    BoxWithConstraints(modifier = stageModifier, contentAlignment = Alignment.Center) {
        if (hasVideo) {
            // Only while the page is actually on screen: attaching the surface is what enables the
            // video track, and there is no point decoding into a page the user is not looking at.
            if (isScreenVisible) {
                // Sized by hand instead of with `aspectRatio`: the fullscreen box is wider than
                // the picture, and a size computed here is the only thing that guarantees the
                // picture is letterboxed rather than stretched across the whole screen.
                val fittedWidth = if (maxWidth / maxHeight > videoAspect) maxHeight * videoAspect else maxWidth
                val fittedHeight = fittedWidth / videoAspect
                VideoOutput(
                    controller = controller,
                    aspectRatio = videoAspect,
                    modifier = Modifier.size(fittedWidth, fittedHeight),
                )
            }
        }

        // The art is drawn over the picture until the first frame lands. A texture that has just
        // been attached — or re-attached after the page was away — holds nothing and would show
        // as a black rectangle, which is exactly what a paused track would look like.
        val coverAlpha by animateFloatAsState(
            targetValue = if (hasVideo && videoReady) 0f else 1f,
            label = "audioVideoStageCover",
        )
        AsyncImage(
            model = item?.coverUrl,
            contentDescription = item?.workTitle,
            // Fitted, not cropped, in fullscreen: the screen is much wider than a cover, and
            // cropping it there leaves a stretched-looking band of art instead of the work.
            contentScale = if (isFullscreen) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(coverAlpha),
        )

        if (hasVideo && !videoReady && isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                strokeWidth = 3.dp,
                color = Color.White,
            )
        }

        if (videoUnsupported) {
            Text(
                text = stringResource(MR.strings.audio_video_unsupported),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        // The only thing a gesture draws: what it is changing and to what, on top of the picture,
        // so a hold or a swipe never feels like it did nothing.
        if (hudText != null) {
            Text(
                text = hudText,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        // Bottom-right corner of the picture itself, not of the page: it belongs to the video and
        // goes away with it. Barely there — a translucent disc only so the glyph stays visible
        // over a bright frame.
        if (hasVideo && !isFullscreen && onFullscreen != null) {
            IconButton(
                onClick = onFullscreen,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(34.dp)
                    .background(Color.Black.copy(alpha = 0.22f), CircleShape),
            ) {
                Icon(
                    Icons.Outlined.Fullscreen,
                    contentDescription = stringResource(MR.strings.audio_video_enter_fullscreen),
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * The texture the player draws into.
 *
 * Attached once, when the view is created, and released when it leaves the composition. The
 * controller keeps ownership of the surface: two player pages can exist at once, and only the one
 * that attached it may take it away again.
 */
@Composable
private fun VideoOutput(
    controller: AudioPlayerController,
    aspectRatio: Float,
    modifier: Modifier = Modifier,
) {
    val owner = remember { Any() }
    DisposableEffect(owner) {
        onDispose { controller.detachVideoOutput(owner) }
    }
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                // Transparent until the first frame, so the cover behind it is what shows while
                // the player is still reading the file.
                isOpaque = false
                controller.attachVideoOutput(owner, this)
            }
        },
        modifier = modifier,
    )
}

private const val DEFAULT_VIDEO_ASPECT = 16f / 9f

/** A 3:4 portrait recording is as tall as the block is allowed to get on a portrait page. */
private const val MIN_STAGE_ASPECT = 0.75f
private const val MAX_STAGE_ASPECT = 2.4f
