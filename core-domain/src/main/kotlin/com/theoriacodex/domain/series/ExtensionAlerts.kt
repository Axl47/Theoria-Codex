package com.theoriacodex.domain.series

data class ExtensionAlerts(val missing: Int = 0, val updates: Int = 0) {
    val count: Int get() = missing + updates

    val lines: List<String>
        get() = listOfNotNull(
            missing.takeIf { it > 0 }?.let(::missingExtensionsLine),
            updates.takeIf { it > 0 }?.let { if (it == 1) "1 update" else "$it updates" },
        )
}

fun missingExtensionsLine(count: Int): String =
    if (count == 1) "1 series needs its extension" else "$count series need their extension"
