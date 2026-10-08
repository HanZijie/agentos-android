// :plugins:samples:todo —— 待办示例 App（org.agentos.sample.todo）：Compose + Material 3 界面、自有 SQLite，
// 内嵌 Agent Plugin（assets/agent-plugin/）并导出 Binder MCP 服务。契约见 docs/next-apps-plan.md 第 3 节。
// SDK 级别、字节码版本和 release 签名由根 build.gradle.kts 统一配置。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.agentos.sample.todo"
    defaultConfig {
        applicationId = "org.agentos.sample.todo"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // R8 下的设备验证：与 release 相同的混淆 / 压缩规则，用调试证书签名，带 debug 的自测入口（src/debug 的接收器）。
        // 只用于测试，不发布：./gradlew :plugins:samples:todo:assembleReleaseTest
        create("releaseTest") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    sourceSets.getByName("releaseTest") {
        java.srcDir("src/debug/java")
        manifest.srcFile("src/debug/AndroidManifest.xml")
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // 数据层 / 工具层测试不依赖 Android；碰到 android.* 的 stub（如 Log）时返回默认值而不是抛异常
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // MCP 服务：McpBinderService（工具注册）；debug 自测入口用 McpBinderClient。R8 规则由 SDK 的 consumer-rules.pro 带过来。
    // 与 SDK 有关的只有 agent/TodoMcpService 这一层薄壳；工具层只依赖 kotlinx-serialization-json 和仓库。
    implementation(project(":sdk:plugin-sdk"))

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
