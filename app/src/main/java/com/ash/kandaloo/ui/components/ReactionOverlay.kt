package com.ash.kandaloo.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ash.kandaloo.data.ReactionEvent
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
fun ReactionOverlay(
    reactions: List<ReactionEvent>,
    modifier: Modifier = Modifier
) {
    val config = LocalConfiguration.current
    val screenHeight = config.screenHeightDp

    Box(
        modifier = modifier
            .width(80.dp)
            .fillMaxHeight(),
        contentAlignment = Alignment.BottomEnd
    ) {
        reactions.forEach { reaction ->
            FloatingReaction(
                emoji = reaction.emoji,
                key = "${reaction.timestamp}_${reaction.senderId}",
                screenHeight = screenHeight
            )
        }
    }
}

@Composable
fun FloatingReaction(
    emoji: String,
    key: String,
    screenHeight: Int
) {
    val animProgress = remember { Animatable(0f) }
    val alphaAnim = remember { Animatable(1f) }
    val horizontalOffset = remember { Random.nextInt(-20, 20) }
    val wobble = remember { Random.nextFloat() * 2f + 1f }

    LaunchedEffect(key) {
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 2500, easing = LinearEasing)
        )
    }

    LaunchedEffect(key) {
        alphaAnim.snapTo(1f)
        kotlinx.coroutines.delay(1800)
        alphaAnim.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 700)
        )
    }

    if (alphaAnim.value > 0f) {
        Text(
            text = emoji,
            fontSize = 28.sp,
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (horizontalOffset + (kotlin.math.sin(animProgress.value * wobble * Math.PI) * 15).toInt()).dp
                            .toPx()
                            .roundToInt(),
                        y = -(animProgress.value * screenHeight * 0.6f).dp
                            .toPx()
                            .roundToInt()
                    )
                }
                .alpha(alphaAnim.value)
                .padding(4.dp)
        )
    }
}
