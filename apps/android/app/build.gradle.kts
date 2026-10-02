plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.aspershupadhyay.latch"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.aspershupadhyay.latch"
        // 30 is the first release with AccessibilityService.takeScreenshot, so no
        // MediaProjection consent or foreground service is needed for screenshots.
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // Debug builds may talk to a gateway over plain http on a laptop or emulator.
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "true")
            manifestPlaceholders["allowCleartext"] = "true"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "false")
            manifestPlaceholders["allowCleartext"] = "false"
            // Signed in CI from repository secrets; see .github/workflows/release.yml.
            val keystore = System.getenv("LATCH_KEYSTORE_PATH")
            if (keystore != null) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(keystore)
                    storePassword = System.getenv("LATCH_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("LATCH_KEY_ALIAS")
                    keyPassword = System.getenv("LATCH_KEY_PASSWORD")
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
