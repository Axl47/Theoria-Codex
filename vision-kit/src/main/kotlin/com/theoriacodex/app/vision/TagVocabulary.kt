package com.theoriacodex.app.vision

import com.theoriacodex.domain.vision.PredictedTag
import java.io.Reader

/**
 * The tagger's output vocabulary, read from the model's `selected_tags.csv` (`tag_id,name,category,count`): one row per
 * score the model returns, in output order. Names are Danbooru tags; categories are 0 general, 4 character and 9 rating.
 * Counts are Danbooru post counts, so a tag's base rate is its count over all posts (the four ratings' counts summed).
 */
class TagVocabulary private constructor(
    private val names: Array<String>,
    private val kinds: Array<PredictedTag.Kind?>,
    private val counts: LongArray,
) {
    val size: Int get() = names.size
    private val posts: Long = counts.indices.filter { kinds[it] == null }.sumOf { counts[it] }.coerceAtLeast(1)
    private val rateByName: Map<String, Double> =
        names.indices.filter { kinds[it] != null }.associate { names[it] to counts[it].toDouble() / posts }

    /**
     * The general and character tags in [scores] (one per vocabulary row) at or above [floor], strongest first.
     * Rating tags are dropped: they say how explicit a picture is, which no tag list in the app uses.
     */
    fun predictions(scores: FloatArray, floor: Float = PredictedTag.STORED_FLOOR): List<PredictedTag> {
        require(scores.size == names.size) { "expected ${names.size} scores, got ${scores.size}" }
        return scores.indices.mapNotNull { i ->
            val kind = kinds[i] ?: return@mapNotNull null
            scores[i].takeIf { it >= floor }?.let { PredictedTag(names[i], it, kind) }
        }.sortedByDescending(PredictedTag::score)
    }

    /** The share of Danbooru posts carrying the tag spelled [name], or null for a name the model does not predict. */
    fun baseRate(name: String): Double? = rateByName[name]

    /** Every predicted tag's base rate, by name. */
    fun baseRates(): Map<String, Double> = rateByName

    companion object {
        private const val GENERAL = 0
        private const val CHARACTER = 4
        private const val RATING = 9

        /** Parses the CSV; throws on a malformed file, which the model store treats as a broken download. */
        fun read(reader: Reader): TagVocabulary {
            val rows = reader.buffered().lineSequence().drop(1).filter(String::isNotBlank).map(::csvFields).toList()
            val names = ArrayList<String>(rows.size)
            val kinds = ArrayList<PredictedTag.Kind?>(rows.size)
            val counts = LongArray(rows.size)
            rows.forEachIndexed { index, fields ->
                require(fields.size >= 4) { "row ${index + 2} has ${fields.size} fields" }
                names += fields[1]
                kinds += when (fields[2].toInt()) {
                    GENERAL -> PredictedTag.Kind.GENERAL
                    CHARACTER -> PredictedTag.Kind.CHARACTER
                    RATING -> null
                    else -> error("row ${index + 2} has unknown category ${fields[2]}")
                }
                counts[index] = fields[3].toLong()
            }
            require(names.isNotEmpty()) { "empty vocabulary" }
            return TagVocabulary(names.toTypedArray(), kinds.toTypedArray(), counts)
        }
    }
}

/** One CSV line's fields; a quoted field may hold commas and doubled quotes (`"don't_say_""lazy"""`). */
internal fun csvFields(line: String): List<String> {
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { fields += field.toString(); field.clear() }
            else -> field.append(c)
        }
        i++
    }
    fields += field.toString()
    return fields
}
