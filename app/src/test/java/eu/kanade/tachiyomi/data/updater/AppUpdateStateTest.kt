package eu.kanade.tachiyomi.data.updater

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppUpdateStateTest {

    @Test
    fun `a dismissal lasts for one visit and the next visit asks again`() {
        // A tap outside the dialog, or back, means "not now".
        AppUpdateState.dismissPrompt()
        assertTrue(AppUpdateState.promptAnswered.value)

        // Moving around inside the app — into the reader and back — is the same visit, so the
        // prompt stays out of the way there.
        assertTrue(AppUpdateState.promptAnswered.value)

        // Opening the app again is a new visit and asks again.
        AppUpdateState.resetPromptForNewVisit()
        assertFalse(AppUpdateState.promptAnswered.value)
    }
}
