package com.ash.kandaloo

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val isLoggedIn = FirebaseAuth.getInstance().currentUser != null

    // Shared state for room navigation
    var currentRoomCode by remember { mutableStateOf("") }
    var isCurrentUserHost by remember { mutableStateOf(false) }
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }

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
                    roomManager.joinRoom(
                        roomCode = roomCode,
                        onSuccess = {
                            navController.navigate("room")
                        },
                        onFailure = { /* handled inside */ }
                    )
                },
                onRejoinRoom = { roomCode ->
                    currentRoomCode = roomCode
                    isCurrentUserHost = false
                    roomManager.removeRejoinEntry(roomCode)
                    roomManager.joinRoom(
                        roomCode = roomCode,
                        onSuccess = {
                            navController.navigate("room")
                        },
                        onFailure = { /* handled inside */ }
                    )
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
                onBack = {
                    navController.popBackStack()
                },
                onStartParty = { uri ->
                    selectedVideoUri = uri
                    navController.navigate("player") {
                        popUpTo("room") { inclusive = true }
                    }
                }
            )
        }

        composable("player") {
            selectedVideoUri?.let { uri ->
                VideoPlayerScreen(
                    videoUri = uri,
                    roomCode = currentRoomCode,
                    roomManager = roomManager,
                    isHost = isCurrentUserHost,
                    onExit = {
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