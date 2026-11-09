package com.theoriacodex.app.customsites

import android.content.Context

/**
 * Which built-in sites have passed a browser check, by host, with the browser's User-Agent. A saved custom
 * site keeps its agent in its profile; built-in sites have no profile, and clearance survives restarts.
 */
internal interface BuiltInAgentStore {
    fun read(): Map<String, String>

    fun save(host: String, userAgent: String)
}

internal class SharedPreferencesBuiltInAgentStore(context: Context) : BuiltInAgentStore {
    private val preferences = context.getSharedPreferences("built_in_browser_agents", Context.MODE_PRIVATE)

    override fun read(): Map<String, String> =
        preferences.all.mapNotNull { (host, agent) -> (agent as? String)?.let { host to it } }.toMap()

    override fun save(host: String, userAgent: String) {
        preferences.edit().putString(host, userAgent).apply()
    }
}
