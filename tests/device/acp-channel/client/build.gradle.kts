// tests/device/acp-channel/client —— 第三方身份的测试客户端：SDK 回归（W5）对测试 Agent 跑全部场景；
// W6 用它以第三方 UID 调用 AgentOS 的 IAcpService，验证被拒。只用于测试，不进 zip。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.agentos.test.acp.client"
    defaultConfig {
        applicationId = "org.agentos.test.acp.client"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (signingConfig == null) signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(project(":tests:device:acp-channel:common"))
}
