package eu.kanade.tachiyomi.data.audio

enum class AudioQualityMode(val preferenceValue: String) {
    FLUENT_FIRST("fluent_first"),
    QUALITY_FIRST("quality_first"),
    ;

    fun next(): AudioQualityMode = when (this) {
        FLUENT_FIRST -> QUALITY_FIRST
        QUALITY_FIRST -> FLUENT_FIRST
    }

    companion object {
        fun fromPreference(value: String): AudioQualityMode = entries
            .firstOrNull { it.preferenceValue == value }
            ?: FLUENT_FIRST
    }
}
