// 示例 App：备忘录（docs/sample-apps.md）。Compose + Material 3，数据在自己的 SQLite 里，
// 内嵌 Agent Plugin（assets/agent-plugin/）并（SDK 合入后）导出 Binder MCP 服务。
// SDK 级别、字节码版本、release 签名由根 build.gradle.kts 统一配置。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.agentos.sample.notes"
    defaultConfig {
        applicationId = "org.agentos.sample.notes"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // 数据层 / 工具层测试不依赖 Android；个别地方碰到 android.util.Log 之类时返回默认值而不是抛异常
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
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)

    // 与 SDK 有关的只有两处薄层：agent/NotesMcpService（McpBinderService，把备忘录暴露给 AgentOS）和
    // agentos/RealAgentOsGateway（AgentOs，经 ACP 让 AgentOS 安排日程 / 闹钟）；工具层只依赖 kotlinx-serialization-json 和仓库
    implementation(project(":sdk:plugin-sdk"))
    implementation(project(":sdk:acp-android"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
