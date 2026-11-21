package com.theoriacodex.app.codex

/** The noun that follows [count]: [singular] for exactly one, otherwise [plural]. */
fun countNoun(count: Int, singular: String, plural: String = "${singular}s"): String = if (count == 1) singular else plural
