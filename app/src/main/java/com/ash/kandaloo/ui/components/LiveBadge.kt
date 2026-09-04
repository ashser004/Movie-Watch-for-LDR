package com.ash.kandaloo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ash.kandaloo.data.LiveMemberInfo

@Composable
fun LiveBadge(
    memberCount: Int,
    memberNames: List<String> = emptyList(),
    liveMembers: List<LiveMemberInfo> = emptyList(),
    modifier: Modifier = Modifier
) {
    var showPopup by remember { mutableStateOf(false) }
    val isAlone = memberCount <= 1

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.15f))
                .clickable { showPopup = true }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isAlone) Color.Red else Color(0xFF4CAF50))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.People,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "$memberCount Live",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall
            )
        }

        DropdownMenu(
            expanded = showPopup,
            onDismissRequest = { showPopup = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
        ) {
            Text(
                text = "Connected Users",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            if (liveMembers.isNotEmpty()) {
                liveMembers.forEach { member ->
                    val dotColor = when {
                        member.isLeft || member.isOffline -> Color(0xFFE53935) // Red
                        member.isUnstable -> Color(0xFFFF7043) // Orange
                        member.isMinimized -> Color(0xFFFFB300) // Amber / Away
                        else -> Color(0xFF4CAF50) // Green / Live
                    }
                    val statusText = when {
                        member.isLeft -> "Left"
                        member.isOffline -> "Offline"
                        member.isUnstable -> "Unstable"
                        member.isMinimized -> "Away"
                        else -> "Watching"
                    }

                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(dotColor)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = member.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = statusText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                if (member.battery in 1..99) {
                                    val batteryColor = when {
                                        member.battery < 10 -> Color(0xFFEF5350)
                                        member.battery < 20 -> Color(0xFFFFA726)
                                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(start = 12.dp)
                                    ) {
                                        Text(
                                            text = "${member.battery}%",
                                            color = batteryColor,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (member.battery < 20) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        },
                        onClick = { showPopup = false }
                    )
                }
            } else if (memberNames.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No active members", style = MaterialTheme.typography.bodyMedium) },
                    onClick = { showPopup = false }
                )
            } else {
                memberNames.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name, style = MaterialTheme.typography.bodyMedium) },
                        onClick = { showPopup = false }
                    )
                }
            }
        }
    }
}
