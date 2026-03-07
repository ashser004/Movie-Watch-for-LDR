package com.ash.kandaloo.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ash.kandaloo.data.ChatMessage
import kotlin.math.roundToInt

@Composable
fun FloatingMessageOverlay(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier
) {
    val config = LocalConfiguration.current
    val screenHeight = config.screenHeightDp

    Box(
        modifier = modifier
            .widthIn(max = 280.dp)
            .fillMaxHeight(),
        contentAlignment = Alignment.BottomStart
    ) {
        messages.forEach { msg ->
            FloatingChatBubble(
                message = msg,
                key = "${msg.timestamp}_${msg.senderId}",
                screenHeight = screenHeight
            )
        }
    }
}

@Composable
fun FloatingChatBubble(
    message: ChatMessage,
    key: String,
    screenHeight: Int
) {
    val animProgress = remember { Animatable(0f) }
    val alphaAnim = remember { Animatable(1f) }

    LaunchedEffect(key) {
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 4000, easing = LinearEasing)
        )
    }

    LaunchedEffect(key) {
        alphaAnim.snapTo(1f)
        kotlinx.coroutines.delay(3000)
        alphaAnim.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 1000)
        )
    }

    val isSystem = message.type == "join" || message.type == "leave"

    if (alphaAnim.value > 0f) {
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = 0,
                        y = -(animProgress.value * screenHeight * 0.4f).dp
                            .toPx()
                            .roundToInt()
                    )
                }
                .alpha(alphaAnim.value)
                .padding(4.dp)
                .background(
                    if (isSystem) Color.White.copy(alpha = 0.15f)
                    else Color.Black.copy(alpha = 0.55f),
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            if (isSystem) {
                Text(
                    text = message.message,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Column {
                    Text(
                        text = message.senderName,
                        color = Color(0xFFE8A0BF),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = message.message,
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 2
                    )
                }
            }
        }
    }
}
