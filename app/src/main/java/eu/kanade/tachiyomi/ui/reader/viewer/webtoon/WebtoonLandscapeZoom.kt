package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import kotlin.math.abs

internal object WebtoonLandscapeZoom {
    const val FULL_HEIGHT_FRACTION = 1f
    const val CONTINUOUS_HEIGHT_FRACTION = 0.8f

    private const val HEIGHT_EPSILON = 0.02f

    fun targetHeightFraction(displayedHeightFraction: Float): Float {
        return if (abs(displayedHeightFraction - FULL_HEIGHT_FRACTION) <= HEIGHT_EPSILON) {
            CONTINUOUS_HEIGHT_FRACTION
        } else {
            FULL_HEIGHT_FRACTION
        }
    }
}
