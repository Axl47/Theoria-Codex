// Derived from Aniyomi (https://github.com/aniyomiorg/aniyomi), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// Trimmed to what extension binaries call. Package, class and member names are fixed by those binaries.
package eu.kanade.tachiyomi.animesource.model

/**
 * One server an episode can be streamed from. [videoList] is null until the source resolves it
 * (`AnimeSource.getVideoList(hoster)`), or already filled when the source knew the videos up front. [lazy] asks the
 * host to resolve it only when chosen.
 */
open class Hoster(
    val hosterUrl: String = "",
    val hosterName: String = "",
    val videoList: List<Video>? = null,
    val internalData: String = "",
    val lazy: Boolean = false,
) {
    companion object {
        /** The name of the single server [toHosterList] makes, which stands for "this source has no servers". */
        const val NO_HOSTER_LIST = "no_hoster_list"

        /** Wraps an episode's videos as one already-resolved server, as interface-14 extensions answer. */
        fun List<Video>.toHosterList(): List<Hoster> = listOf(Hoster(hosterName = NO_HOSTER_LIST, videoList = this))
    }
}
