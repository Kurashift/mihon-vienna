package eu.kanade.tachiyomi.data.coil

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaCoverFetcherTest {

    @Test
    fun `encoded file uri resolves to the real local cover path`() {
        localCoverFile(
            "file:///storage/emulated/0/A_IMPORTANT/MIHON/local/ActiveMover%20(Arikawa%20Satoru)/cover.jpg",
        ).normalizedPath() shouldBe "/storage/emulated/0/A_IMPORTANT/MIHON/local/ActiveMover (Arikawa Satoru)/cover.jpg"
    }

    @Test
    fun `plain absolute cover path remains unchanged`() {
        localCoverFile("/storage/emulated/0/MIHON/local/作者/cover.jpg").normalizedPath() shouldBe
            "/storage/emulated/0/MIHON/local/作者/cover.jpg"
    }

    private fun java.io.File.normalizedPath() = path.replace('\\', '/')
}
