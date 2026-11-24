// Derived from Aniyomi (https://github.com/aniyomiorg/aniyomi), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
@file:Suppress("PropertyName", "VariableNaming")

package eu.kanade.tachiyomi.animesource.model

import java.io.Serializable

interface SEpisode : Serializable {
    var url: String
    var name: String
    var episode_number: Float
    var fillermark: Boolean
    var scanlator: String?
    var date_upload: Long
    var summary: String?
    var preview_url: String?

    companion object {
        fun create(): SEpisode = SEpisodeImpl()
    }
}

class SEpisodeImpl : SEpisode {
    override lateinit var url: String
    override lateinit var name: String
    override var episode_number: Float = -1f
    override var fillermark: Boolean = false
    override var scanlator: String? = null
    override var date_upload: Long = 0
    override var summary: String? = null
    override var preview_url: String? = null
}
