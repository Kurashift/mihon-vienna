package eu.kanade.tachiyomi.ui.reader.viewer

import kotlin.math.abs
import kotlin.math.max

internal object ReaderSwipeGesture {

    enum class Axis {
        UNDECIDED,
        RANDOM,
        READING,
    }

    fun resolveAxis(
        randomDelta: Float,
        readingDelta: Float,
        touchSlop: Int,
        dominanceRatio: Float,
    ): Axis {
        val randomDistance = abs(randomDelta)
        val readingDistance = abs(readingDelta)
        if (max(randomDistance, readingDistance) <= touchSlop) return Axis.UNDECIDED

        return when {
            randomDistance > readingDistance * dominanceRatio -> Axis.RANDOM
            readingDistance > randomDistance * dominanceRatio -> Axis.READING
            max(randomDistance, readingDistance) > touchSlop * AMBIGUOUS_GESTURE_SLOP_MULTIPLIER -> Axis.READING
            else -> Axis.UNDECIDED
        }
    }

    fun isConfirmed(
        randomDelta: Float,
        readingDelta: Float,
        threshold: Int,
        dominanceRatio: Float,
        expectedNegativeDirection: Boolean,
    ): Boolean {
        return abs(randomDelta) > threshold &&
            abs(randomDelta) > abs(readingDelta) * dominanceRatio &&
            (randomDelta < 0) == expectedNegativeDirection
    }
}

private const val AMBIGUOUS_GESTURE_SLOP_MULTIPLIER = 2
