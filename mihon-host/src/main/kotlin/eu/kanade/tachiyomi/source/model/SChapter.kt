// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// Trimmed to what extension binaries call. Package, class and member names are fixed by those binaries.
@file:Suppress("PropertyName", "VariableNaming")

package eu.kanade.tachiyomi.source.model

import android.net.Uri
import java.io.Serializable
import kotlinx.serialization.json.JsonObject

/** A chapter as a source describes it. [url] identifies it to that source. */
interface SChapter : Serializable {
    var url: String
    var name: String
    var chapter_number: Float
    var scanlator: String?
    var date_upload: Long
    var memo: JsonObject

    fun copyFrom(other: SChapter) {
        name = other.name
        url = other.url
        date_upload = other.date_upload
        chapter_number = other.chapter_number
        scanlator = other.scanlator
        memo = other.memo
    }

    companion object {
        fun create(): SChapter = SChapterImpl()
    }
}

class SChapterImpl : SChapter {
    override lateinit var url: String
    override lateinit var name: String
    override var chapter_number: Float = -1f
    override var scanlator: String? = null
    override var date_upload: Long = 0
    override var memo: JsonObject = JsonObject(emptyMap())
}

/**
 * One page of a chapter. [imageUrl] is the image when the source knows it up front; otherwise [url] is a page the
 * source reads the image address from. [uri] is unused but part of the constructor extensions call.
 */
open class Page(val index: Int, val url: String = "", var imageUrl: String? = null, var uri: Uri? = null) {
    val number: Int get() = index + 1
}
