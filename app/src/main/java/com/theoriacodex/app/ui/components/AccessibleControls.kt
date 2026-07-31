package com.theoriacodex.app.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

internal fun Modifier.expandableControlSemantics(
    expanded: Boolean,
    description: String,
    onExpandedChange: (Boolean) -> Unit,
): Modifier {
    return semantics {
        stateDescription = description
        if (expanded) {
            collapse {
                onExpandedChange(false)
                true
            }
        } else {
            expand {
                onExpandedChange(true)
                true
            }
        }
    }
}
