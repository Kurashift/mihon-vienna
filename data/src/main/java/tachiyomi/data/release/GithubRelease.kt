package tachiyomi.data.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contains information about the latest release from GitHub.
 */
@Serializable
data class GithubRelease(
    @SerialName("tag_name")
    val version: String,
    @SerialName("body")
    val info: String,
    @SerialName("html_url")
    val releaseLink: String,
    @SerialName("assets")
    val assets: List<GitHubAsset>,
)

/**
 * Asset class containing asset name, download url, and the digest GitHub computes for the file.
 *
 * [digest] looks like `sha256:3691…`. It is optional in the API (older releases have none), so it
 * is parsed defensively rather than assumed.
 */
@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url")
    val downloadLink: String,
    val digest: String? = null,
    val size: Long = 0,
)
