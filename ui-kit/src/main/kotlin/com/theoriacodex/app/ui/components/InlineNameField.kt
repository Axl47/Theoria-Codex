package com.theoriacodex.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue

/**
 * A name being edited in place of the text that shows it (a Codex title, a saved search's row). It opens focused
 * with [current] selected and keeps the typed text across recreation. Keyboard Done or losing focus finishes it;
 * [onDone] runs once, with the trimmed new name, or null when it is empty or unchanged (and on Back).
 */
@Composable
fun InlineNameField(current: String, textStyle: TextStyle, modifier: Modifier = Modifier, onDone: (String?) -> Unit) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(current, TextRange(0, current.length)))
    }
    val finish by rememberUpdatedState(onDone)
    val state = remember { InlineNameFieldState() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler { state.finish { finish(null) } }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        singleLine = true,
        textStyle = textStyle,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { state.finish { finish(committedName(current, value.text)) } }),
        modifier = modifier
            .focusRequester(focus)
            .onFocusChanged { change ->
                if (state.focused && !change.isFocused) state.finish { finish(committedName(current, value.text)) }
                state.focused = change.isFocused
            },
    )
}

/** Whether the field has held focus, and whether it already finished, so Done followed by the focus loss it causes commits once. */
private class InlineNameFieldState {
    var focused = false
    private var finished = false

    fun finish(action: () -> Unit) {
        if (finished) return
        finished = true
        action()
    }
}

/** The name an inline rename commits: trimmed, or null when it is empty or unchanged. */
internal fun committedName(current: String, typed: String): String? = typed.trim().takeIf { it.isNotEmpty() && it != current }
