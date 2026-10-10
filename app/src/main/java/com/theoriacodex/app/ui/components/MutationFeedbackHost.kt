package com.theoriacodex.app.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.ui.state.MutationFeedback

@Composable
internal fun MutationFeedbackHost(feedback: MutationFeedback, snackbar: SnackbarHostState) {
    val notices by feedback.notices.collectAsStateWithLifecycle()
    val current = notices.firstOrNull()
    LaunchedEffect(current?.id) {
        if (current == null) return@LaunchedEffect
        snackbar.showSnackbar(current.message)
        feedback.acknowledge(current.id)
    }
}
