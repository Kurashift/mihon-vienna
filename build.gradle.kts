buildscript {
    dependencies {
        classpath(libs.kotlin.gradle)
    }
}

plugins {
    alias(libs.plugins.aboutLibraries) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineProfile) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.moko.resources) apply false
    alias(libs.plugins.sqldelight) apply false

    alias(libs.plugins.detekt)
    alias(mihonx.plugins.spotless)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    parallel = true
    config.setFrom(files("config/detekt/detekt.yml"))
    baseline = file("config/detekt/baseline.xml")
    source.setFrom(
        files(
            "app/src/main/java",
            "baseline-profile/src/main/kotlin",
            "core/archive/src/main/kotlin",
            "core/common/src/main/kotlin",
            "core/viewmodel/src/main/kotlin",
            "core-metadata/src/main/java",
            "data/src/main/java",
            "domain/src/main/java",
            "presentation-core/src/main/java",
            "presentation-widget/src/main/java",
            "source-api/src/main/kotlin",
            "source-local/src/main/kotlin",
            "telemetry/src/firebase/kotlin",
            "telemetry/src/noop/kotlin",
        ),
    )
}

val buildLogic: IncludedBuild = gradle.includedBuild("build-logic")
tasks {
    listOf("clean", "spotlessApply", "spotlessCheck").forEach { task ->
        named(task) {
            dependsOn(buildLogic.task(":$task"))
        }
    }
}
