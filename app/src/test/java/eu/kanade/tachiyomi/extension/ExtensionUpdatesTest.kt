package eu.kanade.tachiyomi.extension

import eu.kanade.domain.extension.interactor.GetExtensionsByType
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.util.validateExtensionPackage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExtensionUpdatesTest {

    private val installed = Extension.Installed(
        name = "Test", pkgName = "test.extension", versionName = "1.4.1", versionCode = 1,
        libVersion = 1.4, lang = "en", isNsfw = false, pkgFactory = null,
        sources = emptyList(), icon = null, isShared = false, signatures = listOf("original-key"),
    )

    private fun listing(key: String = "original-key", version: Long = 2, lib: Double = 1.4) = Extension.Available(
        name = "Test", pkgName = installed.pkgName, versionName = "1.4.2", versionCode = version,
        libVersion = lib, lang = "en", isNsfw = false, sources = emptyList(),
        apkUrl = "https://example.com/test.apk", iconUrl = "https://example.com/test.png",
        store = ExtensionStore(
            indexUrl = "https://example.com/$key/repo.json",
            name = key,
            badgeLabel = key,
            signingKey = key,
            contact = ExtensionStore.Contact("https://example.com", null),
            isLegacy = false,
            extensionListUrl = null,
        ),
    )

    @Test
    fun `same package from another signing key cannot supply updates`() {
        val matching = listing()
        val other = listing("other-key", 999)
        assertSame(matching, installed.findUpdate(listOf(other, matching)))
        assertSame(matching, installed.findUpdate(listOf(matching, other)))
        assertNull(installed.findUpdate(listOf(other)))
    }

    @Test
    fun `newest matching listing wins regardless of repository order`() {
        val newest = listing(version = 8)
        assertSame(newest, installed.findUpdate(listOf(newest, listing(version = 2), listing(version = 4))))
    }

    @Test
    fun `normal library upgrade same version and downgrade rules remain`() {
        assertNull(installed.findUpdate(listOf(listing(version = 1))))
        assertNull(installed.findUpdate(listOf(listing(version = 0))))
        assertEquals(1.6, installed.findUpdate(listOf(listing(version = 1, lib = 1.6)))?.libVersion)
        assertNull(installed.findUpdate(emptyList()))
        assertNull(installed.findUpdate(listOf(listing().copy(pkgName = "other.extension"))))
    }

    @Test
    fun `signing history can select a matching store`() {
        val rotated = installed.copy(signatures = listOf("original-key", "new-key"))
        val newest = listing("new-key", 3)
        assertSame(newest, rotated.findUpdate(listOf(listing(), newest)))
    }

    @Test
    fun `download must match listed package version and store signer`() {
        val listing = listing()
        validateExtensionPackage(listing, installed.pkgName, 2, listOf("original-key"))
        assertThrows(IllegalArgumentException::class.java) {
            validateExtensionPackage(listing, "other.extension", 2, listOf("original-key"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateExtensionPackage(listing, installed.pkgName, 1, listOf("original-key"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateExtensionPackage(listing, installed.pkgName, 2, listOf("other-key"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateExtensionPackage(listing, installed.pkgName, 2, emptyList())
        }
    }

    @Test
    fun `source rows keep the real install package while preserving individual names`() = runTest {
        val preferences = mockk<SourcePreferences>()
        every { preferences.showNsfwSource.get() } returns true
        every { preferences.enabledLanguages.changes() } returns flowOf(setOf("en"))
        val available = listing().copy(
            sources = listOf(
                Extension.Available.Source(1, "en", "First source", "https://example.com"),
                Extension.Available.Source(2, "en", "Second source", "https://example.com"),
            ),
        )
        val manager = mockk<ExtensionManager>()
        every { manager.installedExtensionsFlow } returns MutableStateFlow(emptyList())
        every { manager.untrustedExtensionsFlow } returns MutableStateFlow(emptyList())
        every { manager.availableExtensionsFlow } returns MutableStateFlow(listOf(available))

        val rows = GetExtensionsByType(preferences, manager).subscribe().first().available

        assertEquals(listOf("First source", "Second source"), rows.map { it.name })
        assertEquals(listOf(1L, 2L), rows.map { it.sources.single().id })
        assertEquals(listOf(installed.pkgName, installed.pkgName), rows.map { it.pkgName })
        rows.forEach { validateExtensionPackage(it, installed.pkgName, 2, listOf("original-key")) }
    }
}
