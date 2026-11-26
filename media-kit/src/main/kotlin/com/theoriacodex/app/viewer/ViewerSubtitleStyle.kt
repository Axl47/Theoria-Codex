@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.accessibility.CaptioningManager
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

fun SubtitleView.applyViewerSubtitleStyle() {
    setUserDefaultTextSize()
    val captioning = context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
    if (captioning?.isEnabled == true) setUserDefaultStyle() else setStyle(VIEWER_SUBTITLE_STYLE)
    setApplyEmbeddedStyles(true)
    setApplyEmbeddedFontSizes(false)
    setBottomPaddingFraction(VIEWER_SUBTITLE_RESTING_BOTTOM_FRACTION)
}

fun sanitizeViewerSubtitleCue(cue: Cue): Cue {
    if (cue.bitmap != null) return cue
    val builder = cue.buildUpon().clearWindowColor()
    val text = cue.text
    if (text is Spanned) {
        val cleaned = SpannableString.valueOf(text)
        cleaned.getSpans(0, cleaned.length, Any::class.java).forEach { span ->
            if (span is BackgroundColorSpan || span is ForegroundColorSpan) cleaned.removeSpan(span)
        }
        builder.setText(cleaned)
    }
    return builder.build()
}

private const val VIEWER_SUBTITLE_RESTING_BOTTOM_FRACTION = 0.06f
private val VIEWER_SUBTITLE_STYLE = CaptionStyleCompat(
    android.graphics.Color.WHITE,
    android.graphics.Color.TRANSPARENT,
    android.graphics.Color.TRANSPARENT,
    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
    android.graphics.Color.BLACK,
    Typeface.SANS_SERIF,
)
