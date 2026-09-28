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

    // W8 自带界面：Dispatchers.Main（平台 View，不引入 androidx 界面库）
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    // W8：ChatController 的状态测试（虚拟时间）
    testImplementation(libs.kotlinx.coroutines.test)
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
