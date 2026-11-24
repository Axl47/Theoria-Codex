package com.theoriacodex.app.codex

fun countNoun(count: Int, singular: String, plural: String = "${singular}s"): String = if (count == 1) singular else plural
