// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
package com.theoriacodex.extensionhost

internal object ChapterRecognition {
    private const val NUMBER_PATTERN = """([0-9]+)(\.[0-9]+)?(\.?[a-z]+)?"""

    private val basic = Regex("""(?<=ch\.) *$NUMBER_PATTERN""")

    private val number = Regex(NUMBER_PATTERN)

    private val unwanted = Regex("""\b(?:v|ver|vol|version|volume|season|s)[^a-z0-9]*[0-9]+""")

    private val unwantedWhiteSpace = Regex("""\s(?=extra|special|omake)""")

    fun parseChapterNumber(seriesTitle: String, chapterName: String): Float? {
        val lower = chapterName.lowercase()
        val untitled = lower.replace(Regex("""(?<![\p{L}\p{N}])${Regex.escape(seriesTitle.lowercase())}(?![\p{L}\p{N}])"""), "")
        val name = (if (number.containsMatchIn(untitled)) untitled else lower).trim()
            .replace(',', '.')
            .replace('-', '.')
            .replace(unwantedWhiteSpace, "")
        val numbers = number.findAll(name).toList()
        if (numbers.isEmpty()) return null
        if (numbers.size > 1) {
            val untagged = unwanted.replace(name, "")
            (basic.find(untagged) ?: number.find(untagged))?.let { return it.chapterNumber() }
        }
        return numbers.first().chapterNumber()
    }

    private fun MatchResult.chapterNumber(): Float {
        val whole = groupValues[1].toDouble()
        val decimal = groups[2]?.value
        val alpha = groups[3]?.value
        return (whole + if (!decimal.isNullOrEmpty()) decimal.toDouble() else alphaPart(alpha)).toFloat()
    }

    private fun alphaPart(alpha: String?): Double {
        if (alpha.isNullOrEmpty()) return 0.0
        return when {
            "extra" in alpha -> 0.99
            "omake" in alpha -> 0.98
            "special" in alpha -> 0.97
            else -> alpha.trimStart('.').singleOrNull()?.let { letter ->
                (letter.code - ('a'.code - 1)).takeIf { it < 10 }?.div(10.0)
            } ?: 0.0
        }
    }
}
