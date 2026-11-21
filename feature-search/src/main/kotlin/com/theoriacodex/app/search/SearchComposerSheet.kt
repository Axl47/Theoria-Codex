package com.theoriacodex.app.search

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** The most of the window the composer takes, so the results it edits stay in view above it. */
private const val SHEET_MAX_FRACTION = 0.62f

/** Results that always stay visible above the composer, even with the keyboard up. */
private val RESULTS_PEEK = 96.dp

/** On short windows the sheet takes up to this much before results get any room. */
private val SHEET_MIN_HEIGHT = 360.dp

private val PROGRESS_HEIGHT = 2.dp

/**
 * The composer's window. It stays a Dialog, so it owns the keyboard and system Back, but it is laid out as an
 * undimmed sheet along the bottom edge: the results above keep updating as the query changes. A tap above the
 * sheet closes it. [replacing] shows a slim progress line along its top edge while newer results are on the way.
 */
@Composable
internal fun SearchComposerSheet(
    replacing: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        UndimmedDialogWindow()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val windowHeight = maxHeight
            Box(Modifier.fillMaxSize().pointerInput(onDismiss) { detectTapGestures { onDismiss() } })
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
                val sheetHeight = maxOf(
                    minOf(windowHeight * SHEET_MAX_FRACTION, maxHeight - RESULTS_PEEK),
                    minOf(maxHeight, SHEET_MIN_HEIGHT),
                )
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(sheetHeight).testTag("Search composer"),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shadowElevation = 8.dp,
                ) {
                    Column(Modifier.fillMaxSize()) {
                        if (replacing) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().height(PROGRESS_HEIGHT).testTag("Search composer progress"))
                        } else {
                            Spacer(Modifier.height(PROGRESS_HEIGHT))
                        }
                        content()
                    }
                }
            }
        }
    }
}

/** Dialogs dim what is behind them; the composer's results must stay readable. */
@Composable
private fun UndimmedDialogWindow() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    DisposableEffect(window) {
        window?.setDimAmount(0f)
        onDispose {}
    }
}
