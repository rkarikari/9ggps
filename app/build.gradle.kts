plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.navigation.safeargs)
}

// ── Single source of truth for app version ────────────────────────────────────
val appVersion = "1.11"
val appVersionCode = 111

android {
    namespace = "com.nineggps"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nineggps"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // API Keys - Replace with your actual keys
        buildConfigField("String", "OPENWEATHER_API_KEY", "\"YOUR_OPENWEATHER_API_KEY\"")
        buildConfigField("String", "OSRM_BASE_URL", "\"https://router.project-osrm.org/\"")
        buildConfigField("String", "NOMINATIM_BASE_URL", "\"https://nominatim.openstreetmap.org/\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    // ── APK output filename ───────────────────────────────────────────────────
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            if (variant.buildType.name == "release") {
                output?.outputFileName = "9GGPS_v${appVersion}_release.apk"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview"
        )
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // ── Lint ─────────────────────────────────────────────────────────────────
    // On Windows the lint cache JAR inside build/intermediates can be held open
    // by a previous Gradle daemon or by antivirus, causing a FileSystemException
    // during :lintVitalAnalyzeRelease.  Disabling lintVital removes that task
    // from the release pipeline entirely.  Full lint is still available on demand
    // via `./gradlew lint`; it is just not a hard blocker on assembleRelease.
    lint {
        checkReleaseBuilds = false   // disables lintVital* tasks on release builds
        abortOnError       = false   // prevent any residual lint finding from breaking CI
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.viewpager2)

    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.service)

    // Navigation
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)

    // DataStore
    implementation(libs.datastore.preferences)

    // Maps
    implementation(libs.osmdroid)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // Chart
    implementation(libs.mpandroidchart)

    // WorkManager
    implementation(libs.workmanager)

    // Splash Screen
    implementation(libs.androidx.splashscreen)

    // Glide
    implementation(libs.glide)
    ksp(libs.glide.compiler)

    // Location
    implementation(libs.play.services.location)

    // Preference
    implementation(libs.androidx.preference)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.espresso)
}
