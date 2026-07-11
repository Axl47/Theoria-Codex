package com.theoriacodex.domain.encoding

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Decodes percent-escaped bytes as strict UTF-8 without applying form/query semantics.
 *
 * In particular, a literal `+` remains `+`. Malformed escapes, unpaired UTF-16 surrogates,
 * and malformed UTF-8 return null rather than producing a replacement character.
 */
fun decodePercentEncodedUtf8Strict(value: String): String? {
    val bytes = ByteArrayOutputStream(value.length)
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character == '%') {
            if (index + 2 >= value.length) return null
            val high = value[index + 1].digitToIntOrNull(radix = 16) ?: return null
            val low = value[index + 2].digitToIntOrNull(radix = 16) ?: return null
            bytes.write((high shl 4) or low)
            index += 3
        } else {
            val codePoint = value.codePointAt(index)
            if (codePoint in HIGH_SURROGATE_START..LOW_SURROGATE_END) return null
            bytes.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
            index += Character.charCount(codePoint)
        }
    }

    return runCatching {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
    }.getOrNull()
}

private const val HIGH_SURROGATE_START: Int = 0xD800
private const val LOW_SURROGATE_END: Int = 0xDFFF
