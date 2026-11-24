package com.theoriacodex.domain.diagnostics

import com.theoriacodex.domain.series.ANIYOMI_SOURCE_ID_PREFIX
import com.theoriacodex.domain.series.MIHON_SOURCE_ID_PREFIX

fun interface DiagnosticSink {
    fun record(area: String, message: String, error: Throwable?)
}

object Diagnostics {
    @Volatile
    private var sink: DiagnosticSink? = null

    fun install(sink: DiagnosticSink) {
        this.sink = sink
    }

    fun report(area: String, message: String, error: Throwable? = null) {
        runCatching { sink?.record(area, message, error) }
    }
}

object DiagnosticRedaction {
    private val url = Regex("""\b([a-zA-Z][a-zA-Z0-9+.-]*)://(?:[^/\s"'<>?#@]*@)?([^/\s"'<>?#]+)[^\s"'<>]*""")
    private val secretAssignment = Regex(
        """(?i)\b([\w-]*(?:token|api[_-]?key|passw(?:or)?d|secret|cookie|authorization|session|user[_-]?id|login|credential)[\w-]*)""" +
            """(["']?\s*[:=]\s*)(?:(?:bearer|basic)\s+)?("[^"]*"|'[^']*'|[^\s,;&]+)""",
    )
    private val authorizationValue = Regex("""(?i)\b(bearer|basic)\s+[a-z0-9._~+/=-]{8,}""")
    private val email = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")
    private val devicePath = Regex("""/(?:storage/emulated/\d+|data/(?:user/\d+|data)/[\w.]+)/[^\s"':]*""")
    private val extensionSourceId = listOf(MIHON_SOURCE_ID_PREFIX, ANIYOMI_SOURCE_ID_PREFIX).joinToString("|", transform = Regex::escape)
    private val opaqueValue = Regex("""\b(?!(?:$extensionSourceId)-?\d+(?![\w-]))[A-Za-z0-9_-]{32,}\b""")

    fun redact(text: String): String = text
        .replace(url) { match -> "${match.groupValues[1]}://${match.groupValues[2]}/[redacted]" }
        .replace(secretAssignment) { match -> "${match.groupValues[1]}${match.groupValues[2]}[redacted]" }
        .replace(authorizationValue) { match -> "${match.groupValues[1]} [redacted]" }
        .replace(email, "[redacted]")
        .replace(devicePath, "[app file]")
        .replace(opaqueValue, "[redacted]")
}
