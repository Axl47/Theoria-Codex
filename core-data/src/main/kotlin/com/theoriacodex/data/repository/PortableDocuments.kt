package com.theoriacodex.data.repository

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reads only one byte beyond the limit, even when a provider cannot report its document size. */
suspend fun readBoundedDocument(input: InputStream, maximumBytes: Int): ByteArray {
    require(maximumBytes > 0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer, 0, minOf(buffer.size, maximumBytes - output.size() + 1))
        if (count < 0) break
        require(output.size() + count <= maximumBytes) { "Document exceeds the $maximumBytes byte limit." }
        output.write(buffer, 0, count)
    }
    require(output.size() > 0) { "This document is empty." }
    return output.toByteArray()
}

fun decodeDocumentUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()

fun checkDocumentDepth(raw: String, maximumDepth: Int = 64) {
    var depth = 0
    var inString = false
    var escaped = false
    raw.forEach { char ->
        when {
            escaped -> escaped = false
            inString && char == '\\' -> escaped = true
            char == '"' -> inString = !inString
            !inString && (char == '{' || char == '[') -> {
                depth += 1
                require(depth <= maximumDepth) { "Document nesting is too deep" }
            }
            !inString && (char == '}' || char == ']') -> depth -= 1
        }
    }
}
