package com.theoriacodex.sources.common

import org.jsoup.nodes.TextNode

internal fun htmlFragmentToText(html: String): String {
    val body = parseHtmlFragment(html).body()
    body.select("script, style").remove()
    body.allElements.forEach { element ->
        element.textNodes().forEach { node -> node.text(node.wholeText.replace(WHITESPACE_RUN, " ")) }
    }
    body.select("br").forEach { it.replaceWith(TextNode("\n")) }
    return body.wholeText().lines().joinToString("\n") { it.trim() }.trim()
}

private val WHITESPACE_RUN = Regex("\\s+")
