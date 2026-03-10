package com.ash.kandaloo

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ash.kandaloo.data.PreferencesManager
import com.ash.kandaloo.service.RoomManager
import com.ash.kandaloo.ui.screens.HomeScreen
import com.ash.kandaloo.ui.screens.LoginScreen
import com.ash.kandaloo.ui.screens.RoomScreen
import com.ash.kandaloo.ui.screens.SettingsScreen
import com.ash.kandaloo.ui.screens.VideoPlayerScreen
import com.ash.kandaloo.ui.theme.KanDalooTheme
import com.google.firebase.auth.FirebaseAuth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val preferencesManager = PreferencesManager(applicationContext)
        val roomManager = RoomManager()

        setContent {
            val isDarkTheme by preferencesManager.isDarkTheme.collectAsState(initial = true)

            KanDalooTheme(darkTheme = isDarkTheme) {
                KanDalooApp(
                    preferencesManager = preferencesManager,
                    roomManager = roomManager
                )
            }
        }
    }
}

@Composable
fun KanDalooApp(
    preferencesManager: PreferencesManager,
    roomManager: RoomManager
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val isLoggedIn = FirebaseAuth.getInstance().currentUser != null

    // Shared state for room navigation
    var currentRoomCode by remember { mutableStateOf("") }
    var isCurrentUserHost by remember { mutableStateOf(false) }
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    // Track if we're transitioning to player (to suppress false leave notification)
    var isTransitioningToPlayer by remember { mutableStateOf(false) }
    // Dialog state for file not found during rejoin
    var showFileNotFoundDialog by remember { mutableStateOf(false) }
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
                onCreateRoom = { maxMembers ->
                    val roomCode = roomManager.generateRoomCode()
                    currentRoomCode = roomCode
                    isCurrentUserHost = true
                    roomManager.createRoom(
                        roomCode = roomCode,
                        maxMembers = maxMembers,
                        onSuccess = {
                            navController.navigate("room")
                        },
                        onFailure = { /* handled inside */ }
                    )
                },
                onJoinRoom = { roomCode ->
                    currentRoomCode = roomCode
                    isCurrentUserHost = false
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
                                    showFileNotFoundDialog = true
                                }
                            )
                        } else {
                            // File not accessible or URI invalid — show error
                            roomManager.removeRejoinEntry(rejoinInfo.roomCode)
                            showFileNotFoundDialog = true
                        }
                    } else {
                        // No stored video URI — normal rejoin through room screen
                        roomManager.removeRejoinEntry(rejoinInfo.roomCode)
                        roomManager.joinRoom(
                            roomCode = rejoinInfo.roomCode,
                            onSuccess = { navController.navigate("room") },
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