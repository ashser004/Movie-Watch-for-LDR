package com.ash.kandaloo.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ash.kandaloo.data.ChatMessage
import com.ash.kandaloo.service.VoicePlayerManager
import com.ash.kandaloo.service.VoiceRecorder
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.roundToInt

/** Recording state machine for the chat input area */
private enum class VoiceInputState {
    IDLE,       // Default — text field + mic/send button
    RECORDING,  // Recording in progress — waveform + timer + delete + stop
    PREVIEW     // Recording completed — play + waveform + duration + delete + send
}

@Composable
fun ChatSection(
    messages: List<ChatMessage>,
    currentUserId: String,
    onSendMessage: (String, ChatMessage?) -> Unit,
    voicePlayerManager: VoicePlayerManager,
    onSendVoice: ((File, Long, ChatMessage?) -> Unit)? = null,
    onRecordingStateChanged: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var messageText by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    var isTextFieldFocused by remember { mutableStateOf(false) }
    var replyingTo by remember { mutableStateOf<ChatMessage?>(null) }

    // Voice recording state
    var voiceState by remember { mutableStateOf(VoiceInputState.IDLE) }
    val voiceRecorder = remember { VoiceRecorder(context) }
    var recordingElapsedMs by remember { mutableLongStateOf(0L) }
    var recordedFile by remember { mutableStateOf<File?>(null) }
    var recordedDurationMs by remember { mutableLongStateOf(0L) }

    // Notify parent when recording state changes (for audio ducking)
    LaunchedEffect(voiceState) {
        onRecordingStateChanged?.invoke(voiceState == VoiceInputState.RECORDING)
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (voiceRecorder.startRecording()) {
                voiceState = VoiceInputState.RECORDING
            } else {
                Toast.makeText(context, "Failed to start recording", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Microphone permission required", Toast.LENGTH_SHORT).show()
        }
    }

    // Recording timer
    LaunchedEffect(voiceState) {
        if (voiceState == VoiceInputState.RECORDING) {
            while (voiceState == VoiceInputState.RECORDING) {
                recordingElapsedMs = voiceRecorder.getElapsedMs()
                delay(200)
            }
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            if (voiceRecorder.isRecording) voiceRecorder.cancelRecording()
            recordedFile?.delete()
        }
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        // Messages list (scrollable, takes remaining space)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }
            items(messages) { msg ->
                val isSystem = msg.type == "join" || msg.type == "leave" || msg.type == "system"
                if (isSystem) {
                    // System messages are not swipeable
                    ChatBubble(message = msg, isCurrentUser = msg.senderId == currentUserId)
                } else {
                    SwipeableMessageWrapper(
                        onSwipeToReply = { replyingTo = msg }
                    ) {
                        if (msg.type == "voice") {
                            VoiceNoteBubble(
                                message = msg,
                                isCurrentUser = msg.senderId == currentUserId,
                                voicePlayerManager = voicePlayerManager
                            )
                        } else {
                            ChatBubble(
                                message = msg,
                                isCurrentUser = msg.senderId == currentUserId
                            )
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(4.dp)) }
        }
        // ─── Reply preview bar ───
        AnimatedVisibility(
            visible = replyingTo != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            replyingTo?.let { reply ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Accent bar
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(32.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = reply.senderName,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp
                        )
                        Text(
                            text = if (reply.type == "voice") "\uD83C\uDFA4 Voice message" else reply.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(
                        onClick = { replyingTo = null },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cancel reply",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // ─── Input area — switches based on voice state ───
        AnimatedContent(
            targetState = voiceState,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "voice_input_state"
        ) { state ->
            when (state) {
                VoiceInputState.IDLE -> {
                    // Normal text input with mic/send swap
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .imePadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = messageText,
                            onValueChange = { messageText = it },
                            placeholder = {
                                Text(
                                    "Type a message...",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { isTextFieldFocused = it.isFocused },
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (messageText.isNotBlank()) {
                                        onSendMessage(messageText.trim(), replyingTo)
                                        messageText = ""
                                        replyingTo = null
                                        focusManager.clearFocus()
                                    }
                                }
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Dynamic mic/send button — only show send when there's text
                        val showSend = messageText.isNotBlank()
                        IconButton(
                            onClick = {
                                if (showSend) {
                                    // Send text message
                                    if (messageText.isNotBlank()) {
                                        onSendMessage(messageText.trim(), replyingTo)
                                        messageText = ""
                                        replyingTo = null
                                        focusManager.clearFocus()
                                    }
                                } else {
                                    // Start recording
                                    if (onSendVoice == null) return@IconButton
                                    val hasPermission = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED

                                    if (hasPermission) {
                                        if (voiceRecorder.startRecording()) {
                                            voiceState = VoiceInputState.RECORDING
                                        } else {
                                            Toast.makeText(context, "Failed to start recording", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    if (showSend && messageText.isNotBlank())
                                        Brush.linearGradient(
                                            colors = listOf(
                                                MaterialTheme.colorScheme.primary,
                                                MaterialTheme.colorScheme.secondary
                                            )
                                        )
                                    else if (showSend)
                                        Brush.linearGradient(
                                            colors = listOf(
                                                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                            )
                                        )
                                    else
                                        Brush.linearGradient(
                                            colors = listOf(
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                            )
                                        )
                                )
                        ) {
                            Icon(
                                if (showSend) Icons.AutoMirrored.Filled.Send else Icons.Default.Mic,
                                contentDescription = if (showSend) "Send" else "Record voice",
                                tint = if (showSend && messageText.isNotBlank())
                                    MaterialTheme.colorScheme.onPrimary
                                else if (showSend)
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                VoiceInputState.RECORDING -> {
                    // Recording UI: waveform + timer + delete + stop + send
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Recording indicator + waveform
                        RecordingWaveform(
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.height(20.dp)
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        // Timer
                        Text(
                            text = formatVoiceDuration(recordingElapsedMs),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        // Delete button
                        IconButton(
                            onClick = {
                                voiceRecorder.cancelRecording()
                                voiceState = VoiceInputState.IDLE
                                recordingElapsedMs = 0L
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete recording",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Stop button
                        IconButton(
                            onClick = {
                                val result = voiceRecorder.stopRecording()
                                if (result != null) {
                                    recordedFile = result.file
                                    recordedDurationMs = result.durationMs
                                    voiceState = VoiceInputState.PREVIEW
                                } else {
                                    Toast.makeText(context, "Recording failed", Toast.LENGTH_SHORT).show()
                                    voiceState = VoiceInputState.IDLE
                                }
                                recordingElapsedMs = 0L
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Stop recording",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Send button (stop + send in one go)
                        IconButton(
                            onClick = {
                                val result = voiceRecorder.stopRecording()
                                recordingElapsedMs = 0L
                                if (result != null) {
                                    voiceState = VoiceInputState.IDLE
                                    onSendVoice?.invoke(result.file, result.durationMs, replyingTo)
                                    replyingTo = null
                                } else {
                                    Toast.makeText(context, "Recording failed", Toast.LENGTH_SHORT).show()
                                    voiceState = VoiceInputState.IDLE
                                }
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.secondary
                                        )
                                    )
                                )
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send voice",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                VoiceInputState.PREVIEW -> {
                    // Preview UI: play + waveform + duration + delete + send
                    val isPreviewPlaying by voicePlayerManager.isPlaying.collectAsState()
                    val previewProgress by voicePlayerManager.progress.collectAsState()
                    val previewPlayingUrl by voicePlayerManager.playingUrl.collectAsState()
                    val isThisPreviewPlaying = isPreviewPlaying &&
                            recordedFile != null &&
                            previewPlayingUrl == recordedFile!!.absolutePath

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Play/Pause preview
                        IconButton(
                            onClick = {
                                recordedFile?.let { file ->
                                    voicePlayerManager.playLocal(file)
                                }
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(
                                if (isThisPreviewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isThisPreviewPlaying) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Waveform
                        WaveformBar(
                            progress = if (isThisPreviewPlaying) previewProgress else 0f,
                            isCurrentUser = true,
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Duration
                        Text(
                            text = formatVoiceDuration(recordedDurationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        // Delete
                        IconButton(
                            onClick = {
                                voicePlayerManager.stop()
                                recordedFile?.delete()
                                recordedFile = null
                                recordedDurationMs = 0L
                                voiceState = VoiceInputState.IDLE
                            },
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .border(
                                    width = 1.5.dp,
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                    shape = CircleShape
                                )
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Send
                        IconButton(
                            onClick = {
                                voicePlayerManager.stop()
                                recordedFile?.let { file ->
                                    onSendVoice?.invoke(file, recordedDurationMs, replyingTo)
                                    replyingTo = null
                                }
                                recordedFile = null
                                recordedDurationMs = 0L
                                voiceState = VoiceInputState.IDLE
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.secondary
                                        )
                                    )
                                )
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send voice",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    isCurrentUser: Boolean
) {
    val isSystem = message.type == "join" || message.type == "leave" || message.type == "system"

    if (isSystem) {
        // System message (join/leave notifications)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = message.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontStyle = FontStyle.Italic,
                fontSize = 11.sp
            )
        }
    } else {
        // Chat message
        Column(
            modifier = Modifier.fillMaxWidth(),
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

            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
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
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Column {
                    // Reply context strip
                    if (message.replyToId.isNotEmpty()) {
                        ReplyContextStrip(
                            senderName = message.replyToSenderName,
                            messagePreview = message.replyToMessage,
                            isCurrentUser = isCurrentUser
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        text = message.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isCurrentUser)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/** Reply context strip shown inside bubbles that are replies */
@Composable
fun ReplyContextStrip(
    senderName: String,
    messagePreview: String,
    isCurrentUser: Boolean
) {
    val accentColor = if (isCurrentUser)
        Color.White.copy(alpha = 0.6f)
    else
        MaterialTheme.colorScheme.primary

    val bgColor = if (isCurrentUser)
        Color.White.copy(alpha = 0.12f)
    else
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(2.5.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(accentColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = senderName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                fontSize = 10.sp
            )
            Text(
                text = messagePreview,
                style = MaterialTheme.typography.bodySmall,
                color = if (isCurrentUser)
                    Color.White.copy(alpha = 0.5f)
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp
            )
        }
    }
}

/** Wraps a message bubble with horizontal swipe-to-reply gesture */
@Composable
fun SwipeableMessageWrapper(
    onSwipeToReply: () -> Unit,
    content: @Composable () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 72.dp.toPx() }
    val maxSwipePx = with(density) { 100.dp.toPx() }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var hasTriggered by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        // Reply arrow icon (appears behind the bubble during swipe)
        if (offsetX > 8f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(
                            alpha = (offsetX / swipeThresholdPx).coerceIn(0f, 1f) * 0.3f
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Reply,
                    contentDescription = "Reply",
                    tint = MaterialTheme.colorScheme.primary.copy(
                        alpha = (offsetX / swipeThresholdPx).coerceIn(0.3f, 1f)
                    ),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // The actual bubble content, offset by drag
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            hasTriggered = false
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            // Only allow right swipe (positive direction)
                            val newOffset = (offsetX + dragAmount).coerceIn(0f, maxSwipePx)
                            offsetX = newOffset

                            // Trigger haptic at threshold crossing
                            if (!hasTriggered && offsetX >= swipeThresholdPx) {
                                hasTriggered = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDragEnd = {
                            if (hasTriggered) {
                                onSwipeToReply()
                            }
                            offsetX = 0f
                            hasTriggered = false
                        },
                        onDragCancel = {
                            offsetX = 0f
                            hasTriggered = false
                        }
                    )
                }
        ) {
            content()
        }
    }
}
