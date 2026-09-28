plugins {
    id("com.android.application")
    kotlin("android")
}

val quickjs = providers.gradleProperty("s8.quickjs").get()
val okhttp = providers.gradleProperty("s8.okhttp").get()
val coroutines = providers.gradleProperty("s8.coroutines").get()

// Only the two runtime files go into the APK (the build also leaves reports in bundle/dist).
val s8Assets = layout.buildDirectory.dir("s8-assets")
val copyBundle by tasks.registering(Copy::class) {
    from(rootProject.file("bundle/dist")) { include("pi-agent.js", "model-catalog.json") }
    into(s8Assets)
}

android {
    namespace = "org.agentos.spike.s8.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.agentos.spike.s8"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "s8"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].java.srcDir("../kotlin-host/src")
    sourceSets["main"].assets.srcDir(s8Assets)
    packaging {
        resources.excludes += "META-INF/*"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

tasks.named("preBuild") { dependsOn(copyBundle) }

dependencies {
    implementation("io.github.dokar3:quickjs-kt:$quickjs")
    implementation("com.squareup.okhttp3:okhttp:$okhttp")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutines")
}
