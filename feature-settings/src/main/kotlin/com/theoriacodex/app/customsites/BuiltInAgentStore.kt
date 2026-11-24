package com.theoriacodex.app.customsites

import android.content.Context

interface BuiltInAgentStore {
    fun read(): Map<String, String>

    fun save(host: String, userAgent: String)
}

class SharedPreferencesBuiltInAgentStore(context: Context) : BuiltInAgentStore {
    private val preferences = context.getSharedPreferences("built_in_browser_agents", Context.MODE_PRIVATE)

    override fun read(): Map<String, String> =
        preferences.all.mapNotNull { (host, agent) -> (agent as? String)?.let { host to it } }.toMap()

    override fun save(host: String, userAgent: String) {
        preferences.edit().putString(host, userAgent).apply()
    }
}
