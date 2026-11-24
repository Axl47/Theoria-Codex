package com.theoriacodex.domain.encoding

import java.net.URLEncoder

fun String.percentEncode(): String = URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")
