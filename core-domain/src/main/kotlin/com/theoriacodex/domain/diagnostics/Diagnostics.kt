package com.theoriacodex.domain.diagnostics

/** Receives failures worth keeping for a later diagnostics report. */
fun interface DiagnosticSink {
    fun record(area: String, message: String, error: Throwable?)
}

/**
 * The app's one failure log, reachable from every module. Call [report] where a failure would otherwise be
 * swallowed; messages should name what failed, never a query, title or other browsing content.
 */
object Diagnostics {
    @Volatile
    private var sink: DiagnosticSink? = null

    fun install(sink: DiagnosticSink) {
        this.sink = sink
    }

    /** Never throws: a broken log must not turn into a second failure at the call site. */
    fun report(area: String, message: String, error: Throwable? = null) {
        runCatching { sink?.record(area, message, error) }
    }
}

/** Strips anything identifying from diagnostic text before it is stored or shared. */
object DiagnosticRedaction {
    private val url = Regex("""\b([a-zA-Z][a-zA-Z0-9+.-]*)://(?:[^/\s"'<>?#@]*@)?([^/\s"'<>?#]+)[^\s"'<>]*""")
    private val secretAssignment = Regex(
        """(?i)\b([\w-]*(?:token|api[_-]?key|passw(?:or)?d|secret|cookie|authorization|session|user[_-]?id|login|credential)[\w-]*)""" +
            """(["']?\s*[:=]\s*)(?:(?:bearer|basic)\s+)?("[^"]*"|'[^']*'|[^\s,;&]+)""",
    )
    private val authorizationValue = Regex("""(?i)\b(bearer|basic)\s+[a-z0-9._~+/=-]{8,}""")
    private val email = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")
    private val devicePath = Regex("""/(?:storage/emulated/\d+|data/(?:user/\d+|data)/[\w.]+)/[^\s"':]*""")
    private val opaqueValue = Regex("""\b[A-Za-z0-9_-]{32,}\b""")

    fun redact(text: String): String = text
        // Addresses go first: their paths and queries hold post ids, tags and keys. The host still locates a failure.
        .replace(url) { match -> "${match.groupValues[1]}://${match.groupValues[2]}/[redacted]" }
        .replace(secretAssignment) { match -> "${match.groupValues[1]}${match.groupValues[2]}[redacted]" }
        .replace(authorizationValue) { match -> "${match.groupValues[1]} [redacted]" }
        .replace(email, "[redacted]")
        .replace(devicePath, "[app file]")
        .replace(opaqueValue, "[redacted]")
}
