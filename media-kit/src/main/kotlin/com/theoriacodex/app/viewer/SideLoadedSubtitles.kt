@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class)

package com.theoriacodex.app.viewer

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SimpleSubtitleDecoder
import androidx.media3.extractor.text.Subtitle
import androidx.media3.extractor.text.SubtitleDecoder
import androidx.media3.extractor.text.SubtitleDecoderException

/** A side-loaded subtitle whose address does not show its format; [SniffingSubtitleDecoder] reads it from the file. */
internal const val UNDETERMINED_SUBTITLE_MIME = "text/x-theoria-undetermined"

/** How much of a subtitle file [sniffedSubtitleMimeType] reads. */
private const val SUBTITLE_SNIFF_BYTES = 512

/**
 * The subtitle format of [location], from the extension of its path (the query is ignored), or
 * [UNDETERMINED_SUBTITLE_MIME]: extension streams often serve subtitles at addresses with none.
 */
internal fun subtitleMimeType(location: String): String =
    when (Uri.parse(location).lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()) {
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
        else -> UNDETERMINED_SUBTITLE_MIME
    }

/** The subtitle format a file's first bytes show (a WebVTT header, an SSA/ASS script, XML, an SRT cue), or null. */
internal fun sniffedSubtitleMimeType(data: ByteArray, length: Int = data.size): String? {
    val head = String(data, 0, minOf(length, SUBTITLE_SNIFF_BYTES), Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
    return when {
        head.startsWith("WEBVTT") -> MimeTypes.TEXT_VTT
        head.startsWith("[Script Info]", ignoreCase = true) -> MimeTypes.TEXT_SSA
        head.startsWith("<") -> MimeTypes.APPLICATION_TTML
        SRT_FIRST_CUE.containsMatchIn(head) -> MimeTypes.APPLICATION_SUBRIP
        else -> null
    }
}

private val SRT_FIRST_CUE = Regex("""^\d+[ \t]*\R\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}[ \t]*-->""")

/**
 * Media3's renderers, with a text renderer that decodes whole subtitle files as they arrive. Side-loaded subtitles
 * ([StreamMediaSourceFactory]) reach it undecoded; Media3's renderer fails playback on those unless told to.
 */
internal class SideLoadedSubtitleRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        out.add(TextRenderer(output, outputLooper, SideLoadedSubtitleDecoders).apply { experimentalSetLegacyDecodingEnabled(true) })
    }
}

/** Media3's subtitle decoders, plus [SniffingSubtitleDecoder] for [UNDETERMINED_SUBTITLE_MIME]. */
private object SideLoadedSubtitleDecoders : SubtitleDecoderFactory {
    override fun supportsFormat(format: Format): Boolean =
        format.sampleMimeType == UNDETERMINED_SUBTITLE_MIME || SubtitleDecoderFactory.DEFAULT.supportsFormat(format)

    override fun createDecoder(format: Format): SubtitleDecoder =
        if (format.sampleMimeType == UNDETERMINED_SUBTITLE_MIME) SniffingSubtitleDecoder() else SubtitleDecoderFactory.DEFAULT.createDecoder(format)
}

/**
 * Decodes a subtitle file in the format its first bytes show. A side-loaded file is one sample, so this runs once,
 * on the renderer's decoder thread, after the video has started. A file it cannot place shows no subtitles: the text
 * renderer logs the decode error and playback goes on.
 */
private class SniffingSubtitleDecoder : SimpleSubtitleDecoder("SniffingSubtitleDecoder") {
    private val parsers = DefaultSubtitleParserFactory()

    override fun decode(data: ByteArray, length: Int, reset: Boolean): Subtitle {
        val mime = sniffedSubtitleMimeType(data, length) ?: throw SubtitleDecoderException("Unrecognised subtitle format")
        return parsers.create(Format.Builder().setSampleMimeType(mime).build()).parseToLegacySubtitle(data, 0, length)
    }
}
