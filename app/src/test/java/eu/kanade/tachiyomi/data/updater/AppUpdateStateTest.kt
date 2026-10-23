package eu.kanade.tachiyomi.data.updater

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppUpdateStateTest {

    @Test
    fun `a dismissal stays quiet only while the reader is still in the app`() {
        AppUpdateState.dismissPrompt(now = 1_000)
        assertTrue(AppUpdateState.promptAnswered.value)

        // Coming back within the lifetime — from the reader, the installer, another app — keeps the
        // prompt out of the way.
        AppUpdateState.resetPromptIfStale(now = 1_000 + 29_000)
        assertTrue(AppUpdateState.promptAnswered.value)

        // A later visit asks again, which is what tapping outside the dialog is supposed to mean.
        AppUpdateState.resetPromptIfStale(now = 1_000 + 31_000)
        assertFalse(AppUpdateState.promptAnswered.value)
    }
}
