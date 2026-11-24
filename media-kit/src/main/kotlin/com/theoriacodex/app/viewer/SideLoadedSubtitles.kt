@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class)

package com.theoriacodex.app.viewer

import android.content.Context
import android.os.Looper
import androidx.core.net.toUri
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

private const val SUBTITLE_SNIFF_BYTES = 512

internal fun subtitleMimeType(location: String): String? =
    when (location.toUri().lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()) {
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
        else -> null
    }

internal fun sniffedSubtitleMimeType(data: ByteArray, length: Int = data.size): String? {
    val size = minOf(length, SUBTITLE_SNIFF_BYTES)
    val charset = when {
        size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte() -> Charsets.UTF_16BE
        size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte() -> Charsets.UTF_16LE
        else -> Charsets.UTF_8
    }
    val head = String(data, 0, size, charset).removePrefix("\uFEFF").trimStart()
    return when {
        head.startsWith("WEBVTT") -> MimeTypes.TEXT_VTT
        head.startsWith("[Script Info]", ignoreCase = true) -> MimeTypes.TEXT_SSA
        head.startsWith("<?xml") || head.startsWith("<tt") -> MimeTypes.APPLICATION_TTML
        SRT_FIRST_CUE.containsMatchIn(head) -> MimeTypes.APPLICATION_SUBRIP
        else -> null
    }
}

private val SRT_FIRST_CUE = Regex("""^\d+[ \t]*\R\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}[ \t]*-->""")

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

private object SideLoadedSubtitleDecoders : SubtitleDecoderFactory {
    private val captions = setOf(MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_MP4CEA608, MimeTypes.APPLICATION_CEA708)

    override fun supportsFormat(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.TEXT_UNKNOWN || SubtitleDecoderFactory.DEFAULT.supportsFormat(format)

    override fun createDecoder(format: Format): SubtitleDecoder =
        if (format.sampleMimeType in captions) SubtitleDecoderFactory.DEFAULT.createDecoder(format) else SniffingSubtitleDecoder(format)
}

private class SniffingSubtitleDecoder(private val format: Format) : SimpleSubtitleDecoder("SniffingSubtitleDecoder") {
    private val parsers = DefaultSubtitleParserFactory()

    override fun decode(data: ByteArray, length: Int, reset: Boolean): Subtitle {
        val sniffed = format.buildUpon().setSampleMimeType(sniffedSubtitleMimeType(data, length) ?: format.sampleMimeType).build()
        if (!parsers.supportsFormat(sniffed)) throw SubtitleDecoderException("Unrecognised subtitle format")
        return parsers.create(sniffed).parseToLegacySubtitle(data, 0, length)
    }
}
