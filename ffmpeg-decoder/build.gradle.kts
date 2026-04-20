plugins {
    id("com.android.library")
}

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 36
    ndkVersion = "26.3.11579264"

    defaultConfig {
        minSdk = 29

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // Only enable CMake build if the JNI directory exists (setup_ffmpeg.sh has been run)
    val cmakeFile = file("src/main/jni/CMakeLists.txt")
    if (cmakeFile.exists()) {
        externalNativeBuild {
            cmake {
                path = cmakeFile
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.media3.decoder)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)

    // Annotations used by the Java sources copied from Media3
    compileOnly("androidx.annotation:annotation:1.6.0")
    compileOnly("org.checkerframework:checker-qual:3.42.0")
}
