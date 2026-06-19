package com.ash.kandaloo

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ash.kandaloo.data.PreferencesManager
import com.ash.kandaloo.data.RoomSessionDao
import com.ash.kandaloo.data.RoomSessionEntity
import com.ash.kandaloo.service.RoomManager
import com.ash.kandaloo.ui.screens.HomeScreen
import com.ash.kandaloo.ui.screens.LoginScreen
import com.ash.kandaloo.ui.screens.RoomScreen
import com.ash.kandaloo.ui.screens.SettingsScreen
import com.ash.kandaloo.ui.screens.VideoPlayerScreen
import com.ash.kandaloo.ui.theme.KanDalooTheme
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermissionIfNeeded()

        val app = application as KanDalooApplication
        val preferencesManager = app.container.preferencesManager
        val roomManager = app.container.roomManager
        val roomSessionDao = app.container.roomSessionDao

        setContent {
            val isDarkTheme by preferencesManager.isDarkTheme.collectAsState(initial = true)

            KanDalooTheme(darkTheme = isDarkTheme) {
                KanDalooApp(
                    preferencesManager = preferencesManager,
                    roomManager = roomManager,
                    roomSessionDao = roomSessionDao
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val alreadyGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!alreadyGranted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }
}

@Composable
fun KanDalooApp(
    preferencesManager: PreferencesManager,
    roomManager: RoomManager,
    roomSessionDao: RoomSessionDao
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isLoggedIn = FirebaseAuth.getInstance().currentUser != null

    // Shared state for room navigation
    var currentRoomCode by remember { mutableStateOf("") }
    var isCurrentUserHost by remember { mutableStateOf(false) }
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    // Track if we're transitioning to player (to suppress false leave notification)
    var isTransitioningToPlayer by remember { mutableStateOf(false) }
    // Dialog state for file not found during rejoin
    var showFileNotFoundDialog by remember { mutableStateOf(false) }
    var showRejoinLobbyDialog by remember { mutableStateOf(false) }
    var pendingRejoinRoomCode by remember { mutableStateOf("") }
    // Track if entering player from a rejoin action
    var isRejoining by remember { mutableStateOf(false) }

    // Check if a content URI is still accessible
    fun isUriAccessible(uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    // File not found dialog
    if (showFileNotFoundDialog) {
        AlertDialog(
            onDismissRequest = { showFileNotFoundDialog = false },
            title = { Text("Cannot Rejoin") },
            text = { Text("The video file has been deleted or moved from its previous location. You can no longer rejoin this party.") },
            confirmButton = {
                TextButton(onClick = { showFileNotFoundDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    // Rejoin via lobby fallback dialog (for expired permissions or moved files)
    if (showRejoinLobbyDialog) {
        AlertDialog(
            onDismissRequest = { showRejoinLobbyDialog = false },
            title = { Text("Video File Not Found") },
            text = { Text("The video file is no longer accessible at its previous location. Would you like to enter the room lobby and select the file again?") },
            confirmButton = {
                TextButton(onClick = {
                    showRejoinLobbyDialog = false
                    roomManager.removeRejoinEntry(pendingRejoinRoomCode)
                    roomManager.joinRoom(
                        roomCode = pendingRejoinRoomCode,
                        onSuccess = { navController.navigate("room") },
                        onFailure = { /* handled */ }
                    )
                }) {
                    Text("Go to Lobby")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRejoinLobbyDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val startDestination = if (isLoggedIn) "home" else "login"

    NavHost(navController = navController, startDestination = startDestination) {
        composable("login") {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate("home") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }

        composable("home") {
            HomeScreen(
                roomManager = roomManager,
                roomSessionDao = roomSessionDao,
                onCreateRoom = { maxMembers ->
                    val roomCode = roomManager.generateRoomCode()
                    currentRoomCode = roomCode
                    isCurrentUserHost = true
                    roomManager.createRoom(
                        roomCode = roomCode,
                        maxMembers = maxMembers,
                        onSuccess = {
                            // Save to local database
                            val user = FirebaseAuth.getInstance().currentUser
                            scope.launch {
                                roomSessionDao.upsert(
                                    RoomSessionEntity(
                                        roomCode = roomCode,
                                        hostName = user?.displayName ?: "Host",
                                        hostId = user?.uid ?: "",
                                        isHost = true,
                                        joinedAt = System.currentTimeMillis(),
                                        maxMembers = maxMembers
                                    )
                                )
                            }
                            navController.navigate("room")
                        },
                        onFailure = { /* handled inside */ }
                    )
                },
                onJoinRoom = { roomCode ->
                    currentRoomCode = roomCode
                    isCurrentUserHost = false
                    // Save to local database
                    scope.launch {
                        // We'll get the host info from the room once we're in it,
                        // for now save with what we know
                        val user = FirebaseAuth.getInstance().currentUser
                        roomSessionDao.upsert(
                            RoomSessionEntity(
                                roomCode = roomCode,
                                hostName = "",  // Will be updated when room data loads
                                hostId = "",
                                isHost = false,
                                joinedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    navController.navigate("room")
                },
                onRejoinRoom = { rejoinInfo ->
                    currentRoomCode = rejoinInfo.roomCode
                    isCurrentUserHost = rejoinInfo.isHost

                    if (rejoinInfo.videoUriString.isNotEmpty()) {
                        // We have video URI stored — check if file is still accessible
                        val videoUri = try { Uri.parse(rejoinInfo.videoUriString) } catch (_: Exception) { null }
                        if (videoUri != null && isUriAccessible(videoUri)) {
                            // File is accessible — rejoin directly
                            roomManager.rejoinPlayingRoom(
                                roomCode = rejoinInfo.roomCode,
                                onSuccess = { status ->
                                    selectedVideoUri = videoUri
                                    // Update local DB: clear leftAt since we're back in
                                    scope.launch {
                                        roomSessionDao.getSession(rejoinInfo.roomCode)?.let { session ->
                                            roomSessionDao.upsert(session.copy(leftAt = 0L))
                                        }
                                    }
                                    if (status == "playing") {
                                        isRejoining = true
                                        navController.navigate("player") {
                                            popUpTo("home") { inclusive = false }
                                        }
                                    } else {
                                        navController.navigate("room")
                                    }
                                },
                                onFailure = {
                                    roomManager.removeRejoinEntry(rejoinInfo.roomCode)
                                    // Clean up local DB too
                                    scope.launch { roomSessionDao.delete(rejoinInfo.roomCode) }
                                    showFileNotFoundDialog = true
                                }
                            )
                        } else {
                            // File not accessible or URI invalid — offer fallback option to enter room lobby and choose again
                            pendingRejoinRoomCode = rejoinInfo.roomCode
                            showRejoinLobbyDialog = true
                        }
                    } else {
                        // No stored video URI — normal rejoin through room screen
                        roomManager.removeRejoinEntry(rejoinInfo.roomCode)
                        roomManager.joinRoom(
                            roomCode = rejoinInfo.roomCode,
                            onSuccess = {
                                // Update local DB: clear leftAt since we're back in
                                scope.launch {
                                    roomSessionDao.getSession(rejoinInfo.roomCode)?.let { session ->
                                        roomSessionDao.upsert(session.copy(leftAt = 0L))
                                    }
                                }
                                navController.navigate("room")
                            },
                            onFailure = { /* handled */ }
                        )
                    }
                },
                onSettings = {
                    navController.navigate("settings")
                }
            )
        }

        composable("room") {
            RoomScreen(
                roomCode = currentRoomCode,
                isHost = isCurrentUserHost,
                roomManager = roomManager,
                preferencesManager = preferencesManager,
                onBack = {
                    // leaveRoom is handled by RoomScreen's DisposableEffect
                    navController.popBackStack()
                },
                onStartParty = { uri ->
                    selectedVideoUri = uri
                    isTransitioningToPlayer = true
                    isRejoining = false
                    // Update local DB with video URI
                    scope.launch {
                        roomSessionDao.getSession(currentRoomCode)?.let { session ->
                            roomSessionDao.upsert(session.copy(videoUriString = uri.toString()))
                        }
                    }
                    navController.navigate("player") {
                        popUpTo("room") { inclusive = true }
                    }
                },
                isTransitioningToPlayer = isTransitioningToPlayer,
                onTransitionConsumed = { isTransitioningToPlayer = false }
            )
        }

        composable("player") {
            selectedVideoUri?.let { uri ->
                VideoPlayerScreen(
                    videoUri = uri,
                    roomCode = currentRoomCode,
                    roomManager = roomManager,
                    isHost = isCurrentUserHost,
                    isRejoin = isRejoining,
                    onExit = {
                        roomManager.leaveRoom(currentRoomCode, uri.toString())
                        // Update local DB: mark as left with current timestamp and video URI
                        scope.launch {
                            roomSessionDao.getSession(currentRoomCode)?.let { session ->
                                roomSessionDao.upsert(
                                    session.copy(
                                        leftAt = System.currentTimeMillis(),
                                        videoUriString = uri.toString()
                                    )
                                )
                            }
                        }
                        isRejoining = false
                        navController.navigate("home") {
                            popUpTo("home") { inclusive = true }
                        }
                    }
                )
            }
        }

        composable("settings") {
            SettingsScreen(
                preferencesManager = preferencesManager,
                onBack = { navController.popBackStack() },
                onLogout = {
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}