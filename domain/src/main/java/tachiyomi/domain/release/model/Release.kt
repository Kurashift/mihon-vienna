package tachiyomi.domain.release.model

/**
 * Contains information about the latest release.
 *
 * [sha256] and [sizeBytes] come from the release API's own asset metadata (GitHub reports a
 * `sha256:` digest per asset). They are what lets the app download and install the APK itself
 * instead of sending the reader to a browser: the checksum is compared before anything is handed
 * to the system installer, and the size drives the progress bar.
 */
data class Release(
    val version: String,
    val info: String,
    val releaseLink: String,
    val downloadLink: String,
    /** Lowercase hex SHA-256 of the APK, or null when the release did not publish one. */
    val sha256: String? = null,
    /** APK size in bytes, or 0 when unknown. */
    val sizeBytes: Long = 0,
)
