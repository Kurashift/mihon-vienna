package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import kotlin.math.abs

internal object WebtoonZoom {
    const val DEFAULT_SCALE = 1f
    const val MIN_LANDSCAPE_SCALE = 0.05f
    private const val CONTINUOUS_FRACTION = 0.8f

    data class Levels(val reading: Float, val alternate: Float) {
        val minimum: Float
            get() = minOf(reading, alternate)

        fun nextScale(currentScale: Float, animationTarget: Float? = null): Float {
            val scale = animationTarget ?: currentScale
            return if (abs(scale - reading) <= reading * 0.02f) alternate else reading
        }
    }

    fun levels(
        viewportWidth: Int,
        viewportHeight: Int,
        pageHeight: Int,
        zoomOutDisabled: Boolean,
    ): Levels {
        if (zoomOutDisabled || viewportHeight <= 0 || viewportWidth <= viewportHeight || pageHeight <= 0) {
            return Levels(DEFAULT_SCALE, 2f)
        }
        val reading = (viewportHeight.toFloat() / pageHeight).coerceIn(MIN_LANDSCAPE_SCALE, DEFAULT_SCALE)
        val smaller = reading * CONTINUOUS_FRACTION
        val alternate = if (smaller >= MIN_LANDSCAPE_SCALE) smaller else reading / CONTINUOUS_FRACTION
        return Levels(reading, alternate)
    }
}
