package com.theoriacodex.app.vision

import android.graphics.Bitmap
import com.theoriacodex.app.media.PerceptualHash
import com.theoriacodex.domain.model.isSamePicture
import kotlin.math.sqrt

internal class WeightedPicture(val bitmap: Bitmap, val weight: Int)

internal fun distinctPictures(pictures: List<Bitmap>): List<WeightedPicture> {
    val kept = mutableListOf<Pair<Long, Int>>()
    val weights = mutableListOf<Int>()
    pictures.forEachIndexed { index, picture ->
        val hash = picture.perceptualHash() ?: return@forEachIndexed
        val same = kept.indexOfFirst { (other, _) -> isSamePicture(hash, other) }
        if (same >= 0) weights[same]++ else {
            kept += hash to index
            weights += 1
        }
    }
    if (kept.isEmpty()) return pictures.take(1).map { WeightedPicture(it, pictures.size) }
    return kept.mapIndexed { i, (_, index) -> WeightedPicture(pictures[index], weights[i]) }
}

private fun Bitmap.perceptualHash(): Long? {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return PerceptualHash.ofArgb(pixels, width, height)
}

internal fun combineScores(scores: List<FloatArray>, weights: List<Int>): FloatArray {
    require(scores.isNotEmpty() && scores.size == weights.size) { "${scores.size} score sets, ${weights.size} weights" }
    val total = weights.sum()
    val top = (total + 2) / 3
    val order = scores.indices.toMutableList()
    return FloatArray(scores.first().size) { output ->
        order.sortByDescending { scores[it][output] }
        var left = top
        var sum = 0.0
        for (picture in order) {
            val taken = minOf(left, weights[picture])
            sum += scores[picture][output].toDouble() * taken
            left -= taken
            if (left == 0) break
        }
        (sum / top).toFloat()
    }
}

internal fun combineEmbeddings(embeddings: List<FloatArray>, weights: List<Int>): FloatArray {
    require(embeddings.isNotEmpty() && embeddings.size == weights.size) { "${embeddings.size} embeddings, ${weights.size} weights" }
    val mean = FloatArray(embeddings.first().size)
    embeddings.forEachIndexed { i, embedding ->
        val scale = weights[i] / embedding.norm().coerceAtLeast(Float.MIN_VALUE)
        for (d in mean.indices) mean[d] += embedding[d] * scale
    }
    val norm = mean.norm()
    if (norm > 0f) for (d in mean.indices) mean[d] /= norm
    return mean
}

private fun FloatArray.norm(): Float = sqrt(sumOf { it.toDouble() * it }).toFloat()
