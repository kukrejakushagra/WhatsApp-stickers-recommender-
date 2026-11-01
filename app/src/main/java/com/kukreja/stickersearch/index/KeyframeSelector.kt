package com.kukreja.stickersearch.index

import kotlin.math.abs

/**
 * Picks the frames worth showing an AI model: the first meaningful frame, the last frame, and the
 * frames where the picture changes most, spaced apart. Works on small grayscale signatures
 * (e.g. 32×32 luma, alpha-weighted) so it is cheap and unit-testable.
 */
object KeyframeSelector {

    data class Choice(val index: Int, val changeScore: Float)

    /** Mean absolute difference between two signatures, 0 (same) .. 1 (opposite). */
    fun diff(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += abs(a[i] - b[i])
        return s / a.size
    }

    fun isBlank(sig: FloatArray) = sig.all { it < 0.02f } || run {
        val mean = sig.average().toFloat()
        sig.all { abs(it - mean) < 0.01f }
    }

    fun select(signatures: List<FloatArray>, maxFrames: Int = 6, minChange: Float = 0.02f): List<Choice> {
        val n = signatures.size
        if (n == 0) return emptyList()
        if (n == 1) return listOf(Choice(0, 0f))
        val change = FloatArray(n)
        for (i in 1 until n) change[i] = diff(signatures[i], signatures[i - 1])

        val first = (0 until n).firstOrNull { !isBlank(signatures[it]) } ?: 0
        val last = n - 1
        val chosen = sortedSetOf(first, last)
        // Total motion is tiny: the animation looks static, two frames are enough.
        if (change.maxOrNull()!! < minChange) return chosen.map { Choice(it, change[it]) }

        val spacing = maxOf(1, n / (maxFrames * 2))
        val byChange = (1 until n).filter { change[it] >= minChange }.sortedByDescending { change[it] }
        for (i in byChange) {
            if (chosen.size >= maxFrames) break
            if (chosen.all { abs(it - i) > spacing }) chosen += i
        }
        // Long smooth motion with few peaks: fill with evenly spaced frames so the arc is visible.
        if (chosen.size < 3 && n >= 3) chosen += n / 2
        return chosen.map { Choice(it, change[it]) }
    }
}
