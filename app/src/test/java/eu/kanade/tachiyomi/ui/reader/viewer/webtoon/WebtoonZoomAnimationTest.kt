package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.animation.Animator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WebtoonZoomAnimationTest {
    @Test
    fun `pinch or disposal cancels animation without applying its destination`() {
        val animation = WebtoonZoomAnimation()
        val animator = mockk<Animator>(relaxed = true)
        val listeners = mutableListOf<Animator.AnimatorListener>()
        every { animator.addListener(any()) } answers { listeners += firstArg<Animator.AnimatorListener>() }
        every { animator.cancel() } answers { listeners.forEach { it.onAnimationEnd(animator) } }
        var completions = 0
        animation.start(animator, 0.8f) { completions++ }

        animation.cancel()

        assertNull(animation.targetScale)
        assertEquals(0, completions)
        verify(exactly = 1) { animator.cancel() }
    }

    @Test
    fun `stale completion cannot clear replacement animation or apply old scale`() {
        val animation = WebtoonZoomAnimation()
        val old = mockk<Animator>(relaxed = true)
        val replacement = mockk<Animator>(relaxed = true)
        val oldListeners = mutableListOf<Animator.AnimatorListener>()
        val newListeners = mutableListOf<Animator.AnimatorListener>()
        every { old.addListener(any()) } answers { oldListeners += firstArg<Animator.AnimatorListener>() }
        every { replacement.addListener(any()) } answers { newListeners += firstArg<Animator.AnimatorListener>() }
        var oldCompletions = 0
        var newCompletions = 0
        animation.start(old, 0.8f) { oldCompletions++ }
        animation.start(replacement, 1f) { newCompletions++ }

        oldListeners.forEach { it.onAnimationEnd(old) }
        assertEquals(1f, animation.targetScale)
        assertEquals(0, oldCompletions)
        newListeners.forEach { it.onAnimationEnd(replacement) }
        assertNull(animation.targetScale)
        assertEquals(1, newCompletions)
        verify(exactly = 1) { old.cancel() }
    }

    @Test
    fun `completion retains progress suppression until final positioning finishes`() {
        val animation = WebtoonZoomAnimation()
        val animator = mockk<Animator>(relaxed = true)
        val listeners = mutableListOf<Animator.AnimatorListener>()
        every { animator.addListener(any()) } answers { listeners += firstArg<Animator.AnimatorListener>() }
        animation.start(animator, 0.8f) {
            assertEquals(0.8f, animation.targetScale)
        }

        listeners.forEach { it.onAnimationEnd(animator) }

        assertNull(animation.targetScale)
        animation.cancel()
        verify(exactly = 0) { animator.cancel() }
    }
}
