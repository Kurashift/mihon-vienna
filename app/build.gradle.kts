import mihon.gradle.Config
import mihon.gradle.getBuildTime
import mihon.gradle.getLatestCommitCount
import mihon.gradle.getLatestCommitSha
import mihon.gradle.tasks.ReplaceShortcutsPlaceholderTask
import java.io.FileInputStream
import java.util.Properties
import kotlin.io.encoding.Base64

plugins {
    alias(mihonx.plugins.android.application)
    alias(mihonx.plugins.compose)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.aboutLibraries)
    alias(libs.plugins.androidx.baselineProfile)
    alias(libs.plugins.kotlin.serialization)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")

android {
    namespace = "eu.kanade.tachiyomi"

    defaultConfig {
        applicationId = "app.mihon"

        // Must stay above every versionCode ever installed, not just the one in git history:
        // a device can carry a locally-built package with a higher code than any pushed tag.
        // 2.2.1 was first published as 201, then re-published as 203 because a local build
        // installed on the main device used 202.
        versionCode = 204
        versionName = "2.2.2"

        buildConfigField("String", "COMMIT_COUNT", "\"${getLatestCommitCount()}\"")
        buildConfigField("String", "COMMIT_SHA", "\"${getLatestCommitSha()}\"")
        buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLatestCommitTime = false)}\"")
        buildConfigField("boolean", "TELEMETRY_INCLUDED", "${Config.includeTelemetry}")
        buildConfigField("boolean", "UPDATER_ENABLED", "${Config.enableUpdater}")
        buildConfigField("boolean", "LOCAL_SOURCE_FIRST", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (System.getenv("MIHON_GITHUB_RELEASE").toBoolean()) {
        val tempStoreFile = file(System.getenv("RUNNER_TEMP")).resolve("antsy.keystore")

        val storeFileBytes = System.getenv("storeFileBase64").let(Base64::decode)
        tempStoreFile.outputStream().use { it.write(storeFileBytes) }

        signingConfigs {
            named("debug") {
                storeFile = tempStoreFile
                storePassword = System.getenv("storePassword")
                keyAlias = System.getenv("keyAlias")
                keyPassword = System.getenv("keyPassword")
            }
        }
    } else if (keystorePropertiesFile.exists()) {
        val keystoreProperties = FileInputStream(keystorePropertiesFile).use { Properties().apply { load(it) } }

        signingConfigs {
            named("debug") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        val debug = getByName("debug") {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-${getLatestCommitCount()}"
            isPseudoLocalesEnabled = true
        }
        // 本项目日常只维护两个构建类型：debug（调试）与 vienna（正式交付、原位升级）。
        // 其余 buildType（release / foss / preview / localFirst / benchmark）均沿用上游原样保留，
        // 不要删除也不要改动：
        // - vienna 依赖 initWith(release) 继承混淆与签名配置，删掉 release 会连带失效
        // - :baseline-profile 的 Baseline Profile 插件默认针对 release 变体生成
        // - 保留原样可让将来合并上游 Mihon 时此文件的冲突降到最低
        val release = getByName("release") {
            applicationIdSuffix = ".vienna"
            isMinifyEnabled = true
            isShrinkResources = true

            signingConfig = debug.signingConfig

            isProfileable = true

            proguardFiles("proguard-android-optimize.txt", "proguard-rules.pro")

            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLatestCommitTime = true)}\"")
        }

        val commonMatchingFallbacks = listOf(release.name)

        create("foss") {
            initWith(release)

            applicationIdSuffix = ".foss"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        create("preview") {
            initWith(release)

            applicationIdSuffix = ".debug"

            versionNameSuffix = debug.versionNameSuffix

            matchingFallbacks.addAll(commonMatchingFallbacks)

            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLatestCommitTime = false)}\"")
        }
        create("localFirst") {
            initWith(release)

            applicationIdSuffix = ".localfirst"
            matchingFallbacks.addAll(commonMatchingFallbacks)

            buildConfigField("boolean", "LOCAL_SOURCE_FIRST", "true")
        }
        create("vienna") {
            initWith(release)

            // Keep the existing application ID so the formerly named primary build remains an
            // in-place upgrade and preserves app data.
            applicationIdSuffix = ".dev"
            matchingFallbacks.addAll(commonMatchingFallbacks)

            buildConfigField("boolean", "UPDATER_ENABLED", "true")
            buildConfigField("boolean", "LOCAL_SOURCE_FIRST", "true")
        }
        create("benchmark") {
            initWith(release)

            versionNameSuffix = "-benchmark"
            applicationIdSuffix = ".benchmark"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
    }

    sourceSets {
        getByName("preview").res.directories.add("src/debug/res")
        getByName("benchmark").res.directories.add("src/debug/res")
    }

    splits {
        abi {
            isEnable = true
            isUniversalApk = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    packaging {
        jniLibs {
            keepDebugSymbols += listOf(
                "libandroidx.graphics.path",
                "libarchive-jni",
                "libconscrypt_jni",
                "libimagedecoder",
                "libquickjs",
                "libsqlite3x",
            )
                .map { "**/$it.so" }
        }
        resources {
            excludes += setOf(
                "kotlin-tooling-metadata.json",
                "LICENSE.txt",
                "META-INF/**/*.properties",
                "META-INF/**/LICENSE.txt",
                "META-INF/*.properties",
                "META-INF/*.version",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/README.md",
            )
        }
    }

    dependenciesInfo {
        includeInApk = Config.includeDependencyInfo
        includeInBundle = Config.includeDependencyInfo
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=coil3.annotation.ExperimentalCoilApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.InternalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

baselineProfile {
    baselineProfileOutputDir = "baselineProfiles"
    mergeIntoMain = true
}

dependencies {
    baselineProfile(projects.baselineProfile)

    implementation(projects.i18n)
    implementation(projects.core.archive)
    implementation(projects.core.common)
    implementation(projects.core.viewmodel)
    implementation(projects.coreMetadata)
    implementation(projects.sourceApi)
    implementation(projects.sourceLocal)
    implementation(projects.data)
    implementation(projects.domain)
    implementation(projects.presentationCore)
    implementation(projects.presentationWidget)
    implementation(projects.telemetry)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.materialIcons)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.animationGraphics)
    debugImplementation(libs.androidx.compose.uiTooling)
    implementation(libs.androidx.compose.uiToolingPreview)
    implementation(libs.androidx.compose.uiUtil)

    implementation(libs.androidx.interpolator)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.androidx.sqlite.bundled)

    implementation(libs.kotlin.reflect)

    implementation(libs.bundles.kotlinx.coroutines)

    implementation(libs.sqldelight.async)

    implementation(libs.kotlinx.datetime)

    // AndroidX libraries
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appCompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.constraintLayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.coreSplashScreen)
    implementation(libs.androidx.media)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.recyclerView)
    implementation(libs.androidx.viewPager)
    implementation(libs.androidx.profileInstaller)

    implementation(libs.bundles.androidx.lifecycle)

    // Job scheduling
    implementation(libs.androidx.work)

    // RxJava
    implementation(libs.rxJava)

    // Networking
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)
    implementation(libs.conscrypt) // TLS 1.3 support for Android < 10

    // Data serialization (JSON, protobuf, xml)
    implementation(libs.bundles.serialization)

    // HTML parser
    implementation(libs.jsoup)

    // Disk
    implementation(libs.diskLruCache)
    implementation(libs.unifile)

    // Preferences
    implementation(libs.androidx.preference)

    // Dependency injection
    implementation(libs.injekt)

    // Image loading
    implementation(libs.bundles.coil)
    implementation(libs.subsamplingScaleImageView) {
        exclude(module = "image-decoder")
    }
    implementation(libs.image.decoder)

    // UI libraries
    implementation(libs.material)
    implementation(libs.flexibleAdapter)
    implementation(libs.photoView)
    implementation(libs.directionalViewPager) {
        exclude(group = "androidx.viewpager", module = "viewpager")
    }
    implementation(libs.composeRichEditor)
    implementation(libs.aboutLibraries.compose)
    implementation(libs.bundles.voyager)
    implementation(libs.composeMaterialMotion)
    implementation(libs.swipe)
    implementation(libs.composeWebview)
    implementation(libs.composeGrid)
    implementation(libs.reorderable)
    implementation(libs.bundles.markdown)
    implementation(libs.materialKolor)

    // Logging
    implementation(libs.logcat)

    // Shizuku
    implementation(libs.bundles.shizuku)

    // String similarity
    implementation(libs.stringSimilarity)

    // Tests
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // For detecting memory leaks; see https://square.github.io/leakcanary/
    // debugImplementation(libs.leakCanary.android)
    implementation(libs.leakCanary.plumber)

    testImplementation(libs.kotlinx.coroutines.test)
}

// APK 命名规范：MihonVienna-<versionName>-<abi>.apk
//   arm64-v8a  手机/真机（默认）
//   x86_64     安卓模拟器
//   armeabi-v7a 老 32 位设备
//   universal  全架构包
// ABI 从 AGP 生成的默认文件名里解析，不依赖各版本 Variant API 的 filters 类型。
val appVersionName = android.defaultConfig.versionName ?: "unknown"
val apkAbiNames = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val defaultName = output.outputFileName.get()
            val abi = apkAbiNames.firstOrNull { defaultName.contains("-$it-") } ?: "universal"
            val suffix = if (variant.buildType == "vienna") "" else "-${variant.buildType}"
            output.outputFileName.set("MihonVienna-$appVersionName-$abi$suffix.apk")
        }
    }

    onVariants { variant ->
        val resSource = variant.sources.res ?: return@onVariants

        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val replaceShortcutsPlaceholderTask = tasks.register<ReplaceShortcutsPlaceholderTask>(
            "replace${variantName}ShortcutPlaceholder",
        ) {
            applicationId.set(variant.applicationId)
            shortcutsFile.set(
                projectDir.resolve(
                    if (variant.buildType == "localFirst") {
                        "src/localFirst/shortcuts.xml"
                    } else {
                        "src/main/shortcuts.xml"
                    },
                ),
            )
        }
        resSource.addGeneratedSourceDirectory(replaceShortcutsPlaceholderTask) { it.outputDir }
    }

    onVariants(selector().withFlavor("default" to "standard")) {
        // Only excluding in standard flavor because this breaks
        // Layout Inspector's Compose tree
        it.packaging.resources.excludes.add("META-INF/*.version")
    }
}
