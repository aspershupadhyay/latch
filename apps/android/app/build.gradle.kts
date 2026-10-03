plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** Where the in-app updater looks for new builds. */
val RELEASES = "https://github.com/aspershupadhyay/latch/releases"

android {
    namespace = "io.github.aspershupadhyay.latch"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.aspershupadhyay.latch"
        // 30 is the first release with AccessibilityService.takeScreenshot, so no
        // MediaProjection consent or foreground service is needed for screenshots.
        minSdk = 30
        targetSdk = 36
        // CI sets these so every build is newer than the last and the in-app
        // updater can tell them apart; local builds stay at 1.
        versionCode = System.getenv("LATCH_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("LATCH_VERSION_NAME") ?: "0.1.0"
        // The only place the updater may download from; see update/Updater.kt.
        buildConfigField("String", "UPDATE_DOWNLOAD_PREFIX", "\"$RELEASES/download/\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // Debug builds may talk to a gateway over plain http on a laptop or emulator.
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "true")
            manifestPlaceholders["allowCleartext"] = "true"
            // Local debug builds look at the beta pre-release (.github/workflows/test-build.yml).
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$RELEASES/download/beta/latch-update.json\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "false")
            manifestPlaceholders["allowCleartext"] = "false"
            // Releases update from the newest published release (release.yml); test builds of
            // main set LATCH_UPDATE_MANIFEST_URL to the beta pre-release (test-build.yml).
            val manifest = System.getenv("LATCH_UPDATE_MANIFEST_URL")?.takeIf { it.isNotBlank() }
                ?: "$RELEASES/latest/download/latch-update.json"
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$manifest\"")
            // Signed in CI from repository secrets; see docs/platform/android.md. A PKCS12 keystore
            // has one password, so alias and key password default to "latch" and the store password.
            val keystore = System.getenv("LATCH_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
            if (keystore != null) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(keystore)
                    storePassword = System.getenv("LATCH_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("LATCH_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "latch"
                    keyPassword = System.getenv("LATCH_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: storePassword
                }
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // Shared protocol fixtures, read by the same tests that run in Rust.
        getByName("test").resources.srcDir("../../../packages/schemas/v1")
        // The gateway's word lists, so the phone re-checks taps with exactly the same rules.
        getByName("main").assets.srcDir("../../../packages/schemas/v1/policy")
    }

    androidResources {
        // Only words.json is needed at runtime; the conformance table is for tests.
        ignoreAssetsPatterns += "cases.json"
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric renders the real Compose screens for snapshot tests.
        unitTests.isIncludeAndroidResources = true
        // Snapshot mode: ./gradlew testDebugUnitTest -Psnapshots=record (or verify).
        unitTests.all { test ->
            // Optional mirror for Robolectric's Android runtime download (rate-limited CI/sandbox networks).
            System.getenv("ROBOLECTRIC_DEPENDENCY_REPO_URL")?.let { test.systemProperty("robolectric.dependency.repo.url", it) }
            when (project.findProperty("snapshots")) {
                "record" -> test.systemProperty("roborazzi.test.record", "true")
                "verify" -> test.systemProperty("roborazzi.test.verify", "true")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
