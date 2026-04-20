import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

fun secret(name: String): String? =
    providers.gradleProperty(name).orNull ?: providers.environmentVariable(name).orNull

val releaseKeystorePath = secret("KANDALOO_KEYSTORE_FILE")
val releaseKeystorePassword = secret("KANDALOO_KEYSTORE_PASSWORD")
val releaseKeyAlias = secret("KANDALOO_KEY_ALIAS")
val releaseKeyPassword = secret("KANDALOO_KEY_PASSWORD")

val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.ash.kandaloo"
    compileSdk = 36

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = File(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.ash.kandaloo"
        minSdk = 29
        targetSdk = 36
        versionCode = 25
        versionName = "2.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Only build for real ARM phones — excludes x86/x86_64 (PC emulators only)
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            // R8 shrinks unused code/resources and obfuscates class names
            // ProGuard keep rules in proguard-rules.pro protect all reflection-loaded classes
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Prevents double-compression of native .so files — smaller install size, faster startup
            useLegacyPackaging = false
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.database)

    // Google Sign-In (Credential Manager)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.google.id.identity)

    // ExoPlayer / Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource)

    // FFmpeg decoder extension (AC3/EAC3/DTS software decoding)
    implementation(project(":ffmpeg-decoder"))

    // Coil for image loading
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}