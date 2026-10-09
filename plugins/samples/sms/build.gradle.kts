// :plugins:samples:sms —— 短信示例 App（org.agentos.sample.sms）：Compose + Material 3 界面，内嵌 Agent Plugin 并导出 Binder MCP 服务。
// 路线：不当默认短信应用；READ_SMS 读系统短信库，SmsManager 发送。契约见 docs/next-apps-plan.md 第 4 节。
// SDK 级别、字节码版本、release 签名由根 build.gradle.kts 统一配置。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.agentos.sample.sms"
    defaultConfig {
        applicationId = "org.agentos.sample.sms"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // R8 下的设备验证：与 release 相同的混淆 / 压缩规则，用调试证书签名，带 debug 的自测入口（src/debug 的接收器）。
        // 只用于测试，不发布：./gradlew :plugins:samples:sms:assembleReleaseTest
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
    // “让 AgentOS 安排”：agentos/RealAgentOsGateway 是 AgentOs（ACP）的薄适配层，让 AgentOS 把短信里的安排建成日程 / 待办 / 闹钟
    implementation(project(":sdk:acp-android"))

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
