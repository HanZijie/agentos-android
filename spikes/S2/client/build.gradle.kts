plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val spikeKeyPass: String = System.getenv("SPIKE_KEY_PASS") ?: "android"

android {
    namespace = "org.agentos.spike.s2.client"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.agentos.spike.s2.client"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "s2-1"
    }

    // Signed with a different key than :agent, so it can never hold the agent's signature permission.
    signingConfigs {
        create("spikeB") {
            storeFile = rootProject.file("build/keys/spike-b.jks")
            storePassword = spikeKeyPass
            keyAlias = "spike"
            keyPassword = spikeKeyPass
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("spikeB")
        }
    }

    sourceSets {
        getByName("main") {
            aidl.srcDir("../agent/src/main/aidl")
        }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
