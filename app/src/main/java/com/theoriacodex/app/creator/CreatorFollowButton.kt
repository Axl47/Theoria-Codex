package com.theoriacodex.app.creator

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable

@Composable
internal fun CreatorFollowButton(followed: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = if (followed) Icons.Default.HowToReg else Icons.Default.PersonAdd,
            contentDescription = if (followed) "Unfollow artist" else "Follow artist",
        )
    }
}
