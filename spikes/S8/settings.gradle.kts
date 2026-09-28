// S8 spike build (not part of the main build).
//   ./gradlew :desktop:run --args="..."        QuickJS on the JVM (quickjs-kt-jvm)
//   ./gradlew :android:assembleDebug           test app (quickjs-kt-android)
// Version matrix: -Ps8.kotlin=<version> -Ps8.quickjs=<version>
pluginManagement {
    val kotlinVersion = providers.gradleProperty("s8.kotlin").getOrElse("2.2.20")
    val agpVersion = providers.gradleProperty("s8.agp").getOrElse("8.10.1")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version kotlinVersion
        kotlin("android") version kotlinVersion
        id("com.android.application") version agpVersion
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "s8-pi-quickjs"
include(":desktop", ":android")
