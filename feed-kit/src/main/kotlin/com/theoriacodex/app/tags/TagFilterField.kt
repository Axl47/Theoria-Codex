package com.theoriacodex.app.tags

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The "Filter tags" field over a tag list. Its button clears the text; with [onClose] it also closes the
 * field, so the button stays while the field is empty.
 */
@Composable
fun TagFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    val showButton = value.isNotEmpty() || onClose != null
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text("Filter tags") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (showButton) {
            {
                IconButton(onClick = {
                    onValueChange("")
                    onClose?.invoke()
                }) {
                    Icon(Icons.Default.Close, contentDescription = if (onClose != null) "Close tag filter" else "Clear tag filter")
                }
            }
        } else {
            null
        },
    )
}

/** Folds case, underscores and runs of spaces, so "Long  Hair" finds "long_hair". */
fun String.normalizedTagFilterText(): String = trim()
    .lowercase()
    .replace('_', ' ')
    .replace(TAG_FILTER_WHITESPACE, " ")

private val TAG_FILTER_WHITESPACE = Regex("\\s+")
