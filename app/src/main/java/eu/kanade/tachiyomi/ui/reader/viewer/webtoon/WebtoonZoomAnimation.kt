package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.animation.Animator
import androidx.core.animation.doOnEnd

internal class WebtoonZoomAnimation {
    private var animator: Animator? = null

    var targetScale: Float? = null
        private set

    fun start(animator: Animator, targetScale: Float? = null, onFinished: () -> Unit = {}) {
        cancel()
        this.animator = animator
        this.targetScale = targetScale
        animator.doOnEnd {
            if (this.animator === animator) {
                try {
                    onFinished()
                } finally {
                    if (this.animator === animator) {
                        this.animator = null
                        this.targetScale = null
                    }
                }
            }
        }
        animator.start()
    }

    fun cancel() {
        val previous = animator
        animator = null
        targetScale = null
        previous?.cancel()
    }
}
