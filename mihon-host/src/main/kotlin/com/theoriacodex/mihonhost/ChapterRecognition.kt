// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// Trimmed to reading a number from a chapter's name, for chapters whose extension leaves the number out.
package com.theoriacodex.mihonhost

/**
 * Reads a chapter's number from its name, as Mihon does: most extensions leave the number to the app. "Vol.1 Ch.3 -
 * Title" is 3, "Ch. 10.5" is 10.5, and "Chapter 4 extra" is 4.99, so an extra sorts just after its chapter.
 */
internal object ChapterRecognition {
    private const val NUMBER_PATTERN = """([0-9]+)(\.[0-9]+)?(\.?[a-z]+)?"""

    /** "Mokushiroku Alice Vol.1 Ch. 4: Misrepresentation" is 4. */
    private val basic = Regex("""(?<=ch\.) *$NUMBER_PATTERN""")

    /** "Bleach 567: Down With Snowwhite" is 567. */
    private val number = Regex(NUMBER_PATTERN)

    /** Volume, version and season tags, which are numbers but not the chapter's. */
    private val unwanted = Regex("""\b(?:v|ver|vol|version|volume|season|s)[^a-z]?[0-9]+""")

    /** Joins a suffix to its number: "12 special" reads as "12special". */
    private val unwantedWhiteSpace = Regex("""\s(?=extra|special|omake)""")

    /** The number in [chapterName], leaving out [seriesTitle] where it repeats; null when the name has none. */
    fun parseChapterNumber(seriesTitle: String, chapterName: String): Float? {
        val name = chapterName.lowercase()
            .replace(seriesTitle.lowercase(), "").trim()
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
                // "12a" is 12.1, through "12i" at 12.9.
                (letter.code - ('a'.code - 1)).takeIf { it < 10 }?.div(10.0)
            } ?: 0.0
        }
    }
}
