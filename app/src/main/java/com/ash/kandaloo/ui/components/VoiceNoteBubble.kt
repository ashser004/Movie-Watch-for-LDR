package com.ash.kandaloo.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ash.kandaloo.data.ChatMessage
import com.ash.kandaloo.service.VoicePlayerManager
import kotlin.math.abs
import kotlin.math.sin

/**
 * Chat bubble for voice messages — shows play/pause, waveform, and duration.
 * Used in the portrait chat list.
 */
@Composable
fun VoiceNoteBubble(
    message: ChatMessage,
    isCurrentUser: Boolean,
    voicePlayerManager: VoicePlayerManager,
    modifier: Modifier = Modifier
) {
    val isPlaying by voicePlayerManager.isPlaying.collectAsState()
    val playingUrl by voicePlayerManager.playingUrl.collectAsState()
    val progress by voicePlayerManager.progress.collectAsState()

    val isThisPlaying = isPlaying && playingUrl == message.audioUrl

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isCurrentUser) Alignment.End else Alignment.Start
    ) {
        if (!isCurrentUser) {
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 12.dp, bottom = 1.dp)
            )
        }

        Column(
            modifier = Modifier
                .widthIn(min = 180.dp, max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isCurrentUser) 16.dp else 4.dp,
                        bottomEnd = if (isCurrentUser) 4.dp else 16.dp
                    )
                )
                .background(
                    if (isCurrentUser)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                )
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            // Reply context strip
            if (message.replyToId.isNotEmpty()) {
                ReplyContextStrip(
                    senderName = message.replyToSenderName,
                    messagePreview = message.replyToMessage,
                    isCurrentUser = isCurrentUser
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Audio controls row
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Play/Pause button
                IconButton(
                    onClick = {
                        if (message.audioUrl.isNotEmpty()) {
                            voicePlayerManager.play(message.audioUrl)
                        }
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (isCurrentUser)
                                Color.White.copy(alpha = 0.25f)
                            else
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        )
                ) {
                    Icon(
                        if (isThisPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isThisPlaying) "Pause" else "Play",
                        tint = if (isCurrentUser) Color.White else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Waveform visualization
                WaveformBar(
                    progress = if (isThisPlaying) progress else 0f,
                    isCurrentUser = isCurrentUser,
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Duration
                Text(
                    text = formatVoiceDuration(message.audioDurationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrentUser)
                        Color.White.copy(alpha = 0.8f)
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * Simple waveform visualization using deterministic bars.
 */
@Composable
fun WaveformBar(
    progress: Float,
    isCurrentUser: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 28
) {
    val activeColor = if (isCurrentUser) Color.White else MaterialTheme.colorScheme.primary
    val inactiveColor = if (isCurrentUser) Color.White.copy(alpha = 0.3f)
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until barCount) {
            val barProgress = i.toFloat() / barCount.toFloat()
            val isActive = barProgress <= progress
            // Generate pseudo-random heights for visual interest
            val height = remember(i) {
                val seed = sin(i * 1.7 + 0.5) * 0.5 + 0.5
                (seed * 0.7 + 0.3).toFloat() // 30%-100% height
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height((28 * height).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (isActive) activeColor else inactiveColor)
            )
        }
    }
}

/**
 * Animated waveform bars for recording state.
 */
@Composable
fun RecordingWaveform(
    modifier: Modifier = Modifier,
    barCount: Int = 5,
    color: Color = Color.Red
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recording_wave")

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until barCount) {
            val animatedHeight by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 400 + (i * 80),
                        easing = LinearEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar_$i"
            )

            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((20 * animatedHeight).dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(color)
            )
        }
    }
}

fun formatVoiceDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
