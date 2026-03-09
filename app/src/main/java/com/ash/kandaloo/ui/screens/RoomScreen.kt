package com.ash.kandaloo.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ash.kandaloo.data.MemberData
import com.ash.kandaloo.data.PreferencesManager
import com.ash.kandaloo.data.VideoMetadata
import com.ash.kandaloo.service.RoomManager
import com.ash.kandaloo.service.VideoMetadataExtractor
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(
    roomCode: String,
    isHost: Boolean,
    roomManager: RoomManager,
    preferencesManager: PreferencesManager,
    onBack: () -> Unit,
    onStartParty: (Uri) -> Unit,
    isTransitioningToPlayer: Boolean = false,
    onTransitionConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var localMetadata by remember { mutableStateOf<VideoMetadata?>(null) }
    var hostMetadata by remember { mutableStateOf<VideoMetadata?>(null) }
    var members by remember { mutableStateOf<Map<String, MemberData>>(emptyMap()) }
    var roomStatus by remember { mutableStateOf("waiting") }
    var allReady by remember { mutableStateOf(false) }
    var metadataMatch by remember { mutableStateOf<Boolean?>(null) }

    val roomDataFlow = remember { roomManager.observeRoom(roomCode) }
    val roomData by roomDataFlow.collectAsState(initial = emptyMap())

    // Observe local autoplay preference and sync to Firebase
    val localAutoPlay by preferencesManager.isAutoPlay.collectAsState(initial = false)
    LaunchedEffect(localAutoPlay) {
        roomManager.setMemberAutoPlay(roomCode, localAutoPlay)
    }

    // Parse room data
    LaunchedEffect(roomData) {
        if (roomData.isEmpty()) return@LaunchedEffect

        roomStatus = roomData["status"] as? String ?: "waiting"

        @Suppress("UNCHECKED_CAST")
        val membersMap = roomData["members"] as? Map<String, Map<String, Any?>> ?: emptyMap()
        members = membersMap.mapValues { (_, v) ->
            MemberData(
                uid = v["uid"] as? String ?: "",
                displayName = v["displayName"] as? String ?: "",
                photoUrl = v["photoUrl"] as? String ?: "",
                isReady = v["isReady"] as? Boolean ?: false,
                hasMatchingFile = v["hasMatchingFile"] as? Boolean ?: false,
                autoPlay = v["autoPlay"] as? Boolean ?: false
            )
        }

        @Suppress("UNCHECKED_CAST")
        val hostMeta = roomData["videoMetadata"] as? Map<String, Any?>
        if (hostMeta != null) {
            hostMetadata = VideoMetadata.fromMap(hostMeta)
        }

        // Check if all members are ready and have matching files
        allReady = members.values.all { it.isReady && it.hasMatchingFile }

        // If room status changed to playing, start party
        if (roomStatus == "playing" && videoUri != null) {
            onStartParty(videoUri!!)
        }
    }

    // Check metadata match whenever local or host metadata changes
    LaunchedEffect(localMetadata, hostMetadata) {
        if (localMetadata != null && hostMetadata != null && !isHost) {
            val matches = localMetadata!!.matches(hostMetadata!!)
            metadataMatch = matches
            roomManager.setMemberFileMatch(roomCode, matches)
            roomManager.setMemberReady(roomCode, matches)
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        // Take persistable URI permission for large files
        context.contentResolver.takePersistableUriPermission(
            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        videoUri = uri
        val metadata = VideoMetadataExtractor.extract(context, uri)
        localMetadata = metadata
        roomManager.setVideoMetadata(roomCode, metadata, isHost)
        if (isHost) {
            roomManager.setMemberFileMatch(roomCode, true)
            roomManager.setMemberReady(roomCode, true)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Don't send leave/cleanup when transitioning to player screen
            // The party is starting, not leaving
            if (!isTransitioningToPlayer) {
                roomManager.leaveRoom(roomCode)
            }
            onTransitionConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isHost) "Your Party" else "Party Room",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Room Code Display
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Room Code",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = roomCode,
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 6.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(roomCode))
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Room code copied!")
                                    }
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    "Copy code",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Share this code with your friends",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // Video Selection
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Movie,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        if (videoUri != null && localMetadata != null) {
                            Text(
                                "Video Selected ✓",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val durationMin = localMetadata!!.durationMs / 60000
                            val durationSec = (localMetadata!!.durationMs % 60000) / 1000
                            Text(
                                "${localMetadata!!.width}x${localMetadata!!.height} • ${durationMin}m ${durationSec}s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val fileSizeMB = localMetadata!!.fileSize / (1024.0 * 1024.0)
                            Text(
                                "%.1f MB".format(fileSizeMB),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (!isHost && metadataMatch != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                if (metadataMatch == true) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "File matches!",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "File doesn't match the host's file",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = {
                                    videoPicker.launch(arrayOf("video/*"))
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Change Video")
                            }
                        } else {
                            Text(
                                "Select a video to watch",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    videoPicker.launch(arrayOf("video/*"))
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Choose Video File")
                            }
                        }
                    }
                }
            }

            // Members List
            item {
                Text(
                    "Members (${members.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(members.entries.toList()) { (uid, member) ->
                MemberCard(
                    member = member,
                    isCurrentUser = uid == currentUserId,
                    isHost = uid == (roomData["hostId"] as? String ?: ""),
                    showMatchStatus = isHost && uid != currentUserId
                )
            }

            // Start Party Button (Host only)
            if (isHost) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (videoUri != null) {
                                val allAutoPlay = members.values.all { it.autoPlay }
                                roomManager.startParty(roomCode, allAutoPlay)
                                if (!allAutoPlay) {
                                    roomManager.sendSystemMessage(
                                        roomCode,
                                        "Auto-play is off — not all members have it enabled",
                                        "system"
                                    )
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Not all members have auto-play enabled")
                                    }
                                }
                                onStartParty(videoUri!!)
                            }
                        },
                        enabled = allReady && videoUri != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Start Party 🎉",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (!allReady && members.size >= 2) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Waiting for all members to select a matching video...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun MemberCard(
    member: MemberData,
    isCurrentUser: Boolean,
    isHost: Boolean,
    showMatchStatus: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentUser)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = member.displayName.firstOrNull()?.uppercase() ?: "?",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 18.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = member.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    if (isHost) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "👑",
                            fontSize = 14.sp
                        )
                    }
                    if (isCurrentUser) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "(You)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = when {
                        showMatchStatus && member.hasMatchingFile -> "File matches yours ✓"
                        showMatchStatus && !member.hasMatchingFile -> "${member.displayName}'s file doesn't match yours"
                        member.hasMatchingFile -> "Ready"
                        else -> "Waiting for video..."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        showMatchStatus && !member.hasMatchingFile -> MaterialTheme.colorScheme.error
                        member.hasMatchingFile -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    }
                )
            }

            // Status indicator
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        if (member.hasMatchingFile)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.outline
                    )
                    .then(
                        if (member.hasMatchingFile)
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), CircleShape)
                        else Modifier
                    )
            )
        }
    }
}
