package com.theoriacodex.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

@Composable
fun Modifier.focusOnOpen(): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(requester) { requester.requestFocus() }
    return focusRequester(requester)
}
