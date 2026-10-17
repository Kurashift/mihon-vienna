package eu.kanade.tachiyomi.data.audio

/**
 * The sites that serve the audio backend.
 *
 * as.mr is the publisher's own index page and lists exactly these four sites; each one answers the
 * same catalogue and the same accounts, so switching between them is a pure connectivity choice.
 * The JWT stays valid (its issuer is asmr.one, not the host it was minted on), the works, tags and
 * reviews are the same rows, and only latency differs — and it differs a lot: measured from one
 * connection, the same request took 132 ms on one host and 2.4 s on another.
 *
 * The list is hard-coded rather than scraped from as.mr: that page is a single-page app whose
 * bundle carries the names, so reading it would mean parsing JavaScript to learn four constants.
 * A stored URL that is no longer in this list simply falls back to [DEFAULT].
 */
enum class AudioBackend(
    /** Name shown in the picker; the API lives on the same name under `api.`. */
    val siteHost: String,
    val apiBaseUrl: String,
) {
    ASMR_ONE("asmr.one", "https://api.asmr.one"),
    ASMR_300("asmr-300.com", "https://api.asmr-300.com"),
    ASMR_200("asmr-200.com", "https://api.asmr-200.com"),
    ASMR_100("asmr-100.com", "https://api.asmr-100.com"),
    ;

    companion object {
        /**
         * The host the section shipped with, used until the user picks another one. Kept as the
         * default rather than asmr.one so an existing install does not silently change its route.
         */
        val DEFAULT: AudioBackend = ASMR_200

        /** Resolves a stored URL back to its entry, or null for anything no longer offered. */
        fun fromUrl(apiBaseUrl: String): AudioBackend? =
            entries.firstOrNull { it.apiBaseUrl.equals(apiBaseUrl, ignoreCase = true) }

        /** The picker's order: cheapest first, and unmeasured entries last. */
        fun sortedByLatency(latency: Map<String, Long?>): List<AudioBackend> = entries.sortedWith(
            compareBy(
                { latency[it.apiBaseUrl] == null },
                { latency[it.apiBaseUrl] ?: Long.MAX_VALUE },
                { it.ordinal },
            ),
        )
    }
}
