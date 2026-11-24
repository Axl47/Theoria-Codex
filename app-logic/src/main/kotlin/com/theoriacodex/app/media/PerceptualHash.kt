package com.theoriacodex.app.media

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object PerceptualHash {
    const val SIZE = 32
    private const val BAND = 8

    private const val MIN_LUMA_SPREAD = 2.0

    fun ofLuma(luma: DoubleArray): Long? {
        require(luma.size == SIZE * SIZE) { "Expected $SIZE×$SIZE grey levels" }
        if (luma.spread() < MIN_LUMA_SPREAD) return null
        val rows = Array(SIZE) { y -> DoubleArray(BAND) { u -> (0 until SIZE).sumOf { x -> luma[y * SIZE + x] * COSINES[u][x] } } }
        val band = DoubleArray(BAND * BAND) { index ->
            val v = index / BAND
            val u = index % BAND
            (0 until SIZE).sumOf { y -> rows[y][u] * COSINES[v][y] }
        }
        val sorted = band.sorted()
        val median = (sorted[band.size / 2 - 1] + sorted[band.size / 2]) / 2
        return band.fold(0L) { hash, value -> (hash shl 1) or if (value > median) 1L else 0L }
    }

    fun ofArgb(pixels: IntArray, width: Int, height: Int): Long? {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Pixels do not fill $width×$height" }
        val grey = DoubleArray(pixels.size) { index ->
            val pixel = pixels[index]
            ((pixel shr 16) and 0xFF) * 0.299 + ((pixel shr 8) and 0xFF) * 0.587 + (pixel and 0xFF) * 0.114
        }
        val across = boxWeights(width)
        val down = boxWeights(height)
        val narrowed = DoubleArray(height * SIZE) { index ->
            val y = index / SIZE
            across[index % SIZE].sumOf { (x, weight) -> grey[y * width + x] * weight }
        }
        return ofLuma(DoubleArray(SIZE * SIZE) { index -> down[index / SIZE].sumOf { (y, weight) -> narrowed[y * SIZE + index % SIZE] * weight } })
    }

    private fun boxWeights(length: Int): List<List<Pair<Int, Double>>> {
        val scale = length.toDouble() / SIZE
        return List(SIZE) { cell ->
            val start = cell * scale
            val end = start + scale
            (floor(start).toInt() until min(ceil(end).toInt(), length)).map { source ->
                source to (min(end, source + 1.0) - max(start, source.toDouble())) / scale
            }
        }
    }

    private fun DoubleArray.spread(): Double {
        val mean = average()
        return sqrt(sumOf { (it - mean) * (it - mean) } / size)
    }

    private val COSINES = Array(BAND) { k -> DoubleArray(SIZE) { n -> cos(PI * k * (2 * n + 1) / (2 * SIZE)) } }
}
