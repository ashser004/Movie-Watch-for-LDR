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
import androidx.compose.ui.unit.dp

@Composable
fun LiveBadge(
    memberCount: Int,
    memberNames: List<String>,
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
            if (memberNames.isEmpty()) {
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
