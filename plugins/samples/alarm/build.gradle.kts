// :plugins:samples:alarm —— 闹钟示例 App（org.agentos.sample.alarm）：Compose + Material 3 界面、真的会响的系统闹钟，
// 内嵌 Agent Plugin（assets/agent-plugin/）并导出 Binder MCP 服务。契约见 docs/sample-apps.md 4.1 节。
// SDK 级别、字节码版本和 release 签名由根 build.gradle.kts 统一配置。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.agentos.sample.alarm"
    defaultConfig {
        applicationId = "org.agentos.sample.alarm"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // R8 下的设备验证：与 release 相同的混淆 / 压缩规则，用调试证书签名，带 debug 的自测入口（src/debug 的接收器）。
        // 只用于测试，不发布：./gradlew :plugins:samples:alarm:assembleReleaseTest
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
        buildConfig = true
    }
    testOptions {
        // JVM 单元测试里碰到 android.* 的 stub（如 Log）时返回默认值，而不是抛异常
        unitTests.isReturnDefaultValues = true
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
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // MCP 服务：McpBinderService（工具注册）；debug 自测入口用 McpBinderClient。R8 规则由 SDK 的 consumer-rules.pro 带过来
    implementation(project(":sdk:plugin-sdk"))

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
