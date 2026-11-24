// Derived from Aniyomi (https://github.com/aniyomiorg/aniyomi), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
package eu.kanade.tachiyomi.animesource.model

open class Hoster(
    val hosterUrl: String = "",
    val hosterName: String = "",
    val videoList: List<Video>? = null,
    val internalData: String = "",
    val lazy: Boolean = false,
) {
    companion object {
        const val NO_HOSTER_LIST = "no_hoster_list"

        fun List<Video>.toHosterList(): List<Hoster> = listOf(Hoster(hosterName = NO_HOSTER_LIST, videoList = this))
    }
}
