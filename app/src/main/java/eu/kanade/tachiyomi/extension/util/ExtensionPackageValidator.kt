package eu.kanade.tachiyomi.extension.util

import eu.kanade.tachiyomi.extension.model.Extension

internal fun validateExtensionPackage(
    listing: Extension.Available,
    packageName: String,
    versionCode: Long,
    signatures: List<String>,
) {
    require(packageName == listing.pkgName) { "Downloaded extension package does not match ${listing.pkgName}" }
    require(versionCode == listing.versionCode) { "Downloaded extension version does not match ${listing.versionCode}" }
    require(listing.store.signingKey in signatures) { "Extension is not signed with the key of ${listing.store.name}" }
}
