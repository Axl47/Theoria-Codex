package com.theoriacodex.app.viewer.state

data class ViewerSessionIdentity(
    val value: String,
    val queryHash: String? = null,
    val streamKey: String? = null,
) {
    init {
        require(value.isNotBlank()) { "Viewer session identity must not be blank" }
    }
}
