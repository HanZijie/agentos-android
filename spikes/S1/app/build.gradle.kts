plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// One APK per (versionCode, key): build.sh runs this with -Ps1.versionCode=N -Ps1.key=a|b.
// Keys are throwaway spike keys generated into build/keys/ by build.sh (never committed).
val s1VersionCode = (findProperty("s1.versionCode") ?: "1").toString().toInt()
val s1Key = (findProperty("s1.key") ?: "a").toString()
val spikeKeyPass: String = System.getenv("SPIKE_KEY_PASS") ?: "android"

android {
    namespace = "org.agentos.spike.s1"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.agentos.spike.s1"
        minSdk = 35
        targetSdk = 36
        versionCode = s1VersionCode
        versionName = "s1-v$s1VersionCode-$s1Key"
    }

    signingConfigs {
        create("spike") {
            storeFile = rootProject.file("build/keys/spike-$s1Key.jks")
            storePassword = spikeKeyPass
            keyAlias = "spike"
            keyPassword = spikeKeyPass
        }
    }

    buildTypes {
        // The release build (R8, signed with the spike key) mirrors what the module ships.
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("spike")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
