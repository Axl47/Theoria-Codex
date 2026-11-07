package com.theoriacodex.sources.custom

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.theoriacodex.sources.http.HttpRequestSpec
import com.theoriacodex.sources.http.SourceByteResponse
import java.net.URI
import java.net.URLDecoder

/**
 * Serves the captured fixtures under `custom-sites/` (see its README) as a redirect-free transport,
 * keyed by host, path and exact query. Credential parameters are ignored when no route names them:
 * Gelbooru and rule34.xxx answer invalid keys exactly as they answer none. Any request no fixture
 * covers fails the test, so every probe request is backed by captured evidence.
 */
internal class FixtureSiteTransport(vararg sites: String) {
    private val routes: Map<String, List<Route>> = sites.associate(::loadSite).let { loaded ->
        loaded.values.groupBy({ it.first }, { it.second }).mapValues { (_, lists) -> lists.flatten() }
    }

    /** Every URL requested, in order. */
    val requested = mutableListOf<String>()

    suspend fun execute(spec: HttpRequestSpec): SourceByteResponse {
        check(!spec.followRedirects) { "Custom-site requests must not follow redirects" }
        requested += spec.url
        val uri = URI(spec.url)
        val query = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate { pair ->
            pair.substringBefore('=').decode() to pair.substringAfter('=', "").decode()
        }
        val candidates = routes[uri.host].orEmpty().filter { it.path == uri.path }
        val route = candidates.firstOrNull { it.query == query }
            ?: candidates.firstOrNull { it.query == query - CREDENTIAL_PARAMETERS }
            ?: error("No fixture for ${spec.url}")
        return SourceByteResponse(route.status, route.body, route.headers)
    }

    private fun loadSite(site: String): Pair<String, Pair<String, List<Route>>> {
        val manifest = JsonParser.parseString(resource("$site/routes.json").decodeToString()).asJsonObject
        val host = URI(manifest.get("baseUrl").asString).host
        val routes = manifest.getAsJsonArray("routes").map { element ->
            val route = element.asJsonObject
            Route(
                path = route.get("path").asString,
                query = route.getAsJsonObject("query").entrySet().associate { (key, value) -> key to value.asString },
                status = route.get("status").asInt,
                body = resource("$site/${route.get("body").asString}"),
                headers = route.headersOf("contentType" to "Content-Type", "location" to "Location", "www-authenticate" to "WWW-Authenticate"),
            )
        }
        return site to (host to routes)
    }

    private fun JsonObject.headersOf(vararg names: Pair<String, String>): Map<String, List<String>> =
        names.mapNotNull { (field, header) -> get(field)?.asString?.let { header to listOf(it) } }.toMap()

    private data class Route(
        val path: String,
        val query: Map<String, String>,
        val status: Int,
        val body: ByteArray,
        val headers: Map<String, List<String>>,
    )

    companion object {
        private val CREDENTIAL_PARAMETERS = setOf("user_id", "api_key", "login")

        fun resource(path: String): ByteArray =
            checkNotNull(FixtureSiteTransport::class.java.getResourceAsStream("/custom-sites/$path")) { "Missing fixture $path" }
                .use { it.readBytes() }

        private fun String.decode(): String = URLDecoder.decode(this, Charsets.UTF_8)
    }
}
