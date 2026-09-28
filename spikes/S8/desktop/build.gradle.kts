plugins {
    kotlin("jvm")
    application
}

val quickjs = providers.gradleProperty("s8.quickjs").get()
val okhttp = providers.gradleProperty("s8.okhttp").get()
val coroutines = providers.gradleProperty("s8.coroutines").get()

kotlin {
    sourceSets["main"].kotlin.srcDir("../kotlin-host/src")
}

dependencies {
    implementation("io.github.dokar3:quickjs-kt:$quickjs")
    implementation("com.squareup.okhttp3:okhttp:$okhttp")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines")
    implementation("org.json:json:20250517")
}

application {
    mainClass.set("org.agentos.spike.s8.desktop.MainKt")
    applicationDefaultJvmArgs = listOf("-Xss4m")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    // Keys are read from the environment only (MINIMAX_API_KEY, OPENAI_COMPAT_*); never from files.
}
