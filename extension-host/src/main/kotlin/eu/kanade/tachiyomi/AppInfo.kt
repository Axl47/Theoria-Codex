// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// Trimmed to what extension binaries call. Package, class and member names are fixed by those binaries.
package eu.kanade.tachiyomi

/** The host app's version, which extensions may put in a User-Agent. Set once by the host runtime. */
object AppInfo {
    @Volatile internal var hostVersionCode: Int = 0

    @Volatile internal var hostVersionName: String = ""

    fun getVersionCode(): Int = hostVersionCode

    fun getVersionName(): String = hostVersionName

    /** Image types the Viewer decodes. */
    fun getSupportedImageMimeTypes(): List<String> = listOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/avif")
}
