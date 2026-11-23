// Derived from Aniyomi (https://github.com/aniyomiorg/aniyomi), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// Trimmed to what extension binaries call. Package, class and member names are fixed by those binaries.
package eu.kanade.tachiyomi.animesource.model

/** A search option an anime source offers; the same shapes as a manga source's `Filter`. */
sealed class AnimeFilter<T>(val name: String, var state: T) {
    open class Header(name: String) : AnimeFilter<Any>(name, 0)

    open class Separator(name: String = "") : AnimeFilter<Any>(name, 0)

    abstract class Select<V>(name: String, val values: Array<V>, state: Int = 0) : AnimeFilter<Int>(name, state)

    abstract class Text(name: String, state: String = "") : AnimeFilter<String>(name, state)

    abstract class CheckBox(name: String, state: Boolean = false) : AnimeFilter<Boolean>(name, state)

    abstract class TriState(name: String, state: Int = STATE_IGNORE) : AnimeFilter<Int>(name, state) {
        fun isIgnored() = state == STATE_IGNORE

        fun isIncluded() = state == STATE_INCLUDE

        fun isExcluded() = state == STATE_EXCLUDE

        companion object {
            const val STATE_IGNORE = 0
            const val STATE_INCLUDE = 1
            const val STATE_EXCLUDE = 2
        }
    }

    abstract class Group<V>(name: String, state: List<V>) : AnimeFilter<List<V>>(name, state)

    abstract class Sort(name: String, val values: Array<String>, state: Selection? = null) : AnimeFilter<Sort.Selection?>(name, state) {
        data class Selection(val index: Int, val ascending: Boolean)
    }

    override fun equals(other: Any?): Boolean = this === other || (other is AnimeFilter<*> && name == other.name && state == other.state)

    override fun hashCode(): Int = 31 * name.hashCode() + (state?.hashCode() ?: 0)
}

data class AnimeFilterList(val list: List<AnimeFilter<*>>) : List<AnimeFilter<*>> by list {
    constructor(vararg fs: AnimeFilter<*>) : this(fs.asList())
}
