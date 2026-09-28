// :app —— AgentOS App（普通 App，项目证书签名）：主进程（界面、确认、设置）· :agent（运行时、ACP 入口）· :ext（Extension Host）。
// 目录见 docs/implementation-plan.md 第 1 节。SDK 级别、字节码版本和 release 签名由根 build.gradle.kts 统一配置。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.agentos.app"
    defaultConfig {
        applicationId = "org.agentos.app"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        // app/src/main/aidl/org/agentos/internal/：IAgentControl（W6）、IExtensionHost / IExtensionCallback（W14）
        aidl = true
    }
}

dependencies {
    implementation(project(":core:runtime"))
    implementation(project(":core:extensions"))
    implementation(project(":sdk:binder-channel"))
    implementation(project(":sdk:acp-android"))
    implementation(project(":sdk:plugin-sdk"))

    // W6 设备用例的 in-app 执行器（tests/device/acp-channel/inapp）：只进 debug 包，release 包里没有
    debugImplementation(project(":tests:device:acp-channel:inapp"))

    testImplementation(libs.junit4)
}

// ---- Pi Agent core 打包（W3）----
// core/pi-runtime 是 Node 工程：build.mjs 用 esbuild 把 Pi Agent core 打成 src/main/assets/pi-agent.js，
// 并导出 src/main/assets/model-catalog.json。两者都是生成物，不进仓库。
// build.mjs 还不存在时跳过；只跑单元测试时可以用 -Pagentos.skipPiBundle=true 显式跳过。
// 第一次构建前先在 core/pi-runtime 里执行 npm ci。
val piRuntimeDir: Directory = rootProject.layout.projectDirectory.dir("core/pi-runtime")
val piBuildScript: File = piRuntimeDir.file("build.mjs").asFile
val skipPiBundle: Provider<Boolean> =
    providers.gradleProperty("agentos.skipPiBundle").map { it.toBoolean() }.orElse(false)

val bundlePiAgent = tasks.register<Exec>("bundlePiAgent") {
    group = "agentos"
    description = "运行 core/pi-runtime/build.mjs，生成 assets/pi-agent.js 和 assets/model-catalog.json"
    workingDir = piRuntimeDir.asFile
    commandLine("node", "build.mjs")
    inputs.files(
        fileTree(piRuntimeDir) {
            include("build.mjs", "package.json", "package-lock.json", "src/**")
        },
    ).withPropertyName("piRuntimeSources")
    outputs.files(
        layout.projectDirectory.file("src/main/assets/pi-agent.js"),
        layout.projectDirectory.file("src/main/assets/model-catalog.json"),
    ).withPropertyName("piBundle")
    onlyIf("core/pi-runtime/build.mjs 存在，且没有设置 agentos.skipPiBundle") {
        piBuildScript.isFile && !skipPiBundle.get()
    }
}

tasks.named("preBuild") {
    dependsOn(bundlePiAgent)
}

// ---- Pi Agent core 的 JS 引擎与设备测试（lane B，W6/B3）----
// app/.../agent/QuickJsEngine.kt 用 quickjs-kt-android（S8 固定 1.0.15）。原生库只打 arm64-v8a：
// 目标真机（Pixel 8）和本机的 arm64 模拟器都够用；需要 x86_64 模拟器时再加。
// androidTest：在 :agent 进程里跑 core:runtime testFixtures 的 PiAdapterContract（同一套契约场景）、
// 冷启动测量和可选的真实端点冒烟（app/src/androidTest）。
android {
    defaultConfig {
        ndk {
            abiFilters += "arm64-v8a"
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(libs.quickjs.kt)

    androidTestImplementation(testFixtures(project(":core:runtime")))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlin.test.junit)
}
