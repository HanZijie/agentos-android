// tests/device/acp-channel/agent —— SDK 回归用的测试 Agent App：官方 ACP SDK 的 Agent 端跑在 :agent 进程，
// 经 sdk:acp-android 的 BinderAcpTransport 导出 IAcpService；假 Agent 按 prompt 里的 JSON 指令流式输出。
// 只用于测试（W5 回归），不进 zip。由 S3 第二部分的 server App 改写。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.agentos.test.acp.agent"
    defaultConfig {
        applicationId = "org.agentos.test.acp.agent"
    }
    buildTypes {
        release {
            // 回归要覆盖 R8：只用库自带的 consumer 规则，App 自己不加任何 ACP 相关规则
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // 测试 App 没有发布证书时用 debug 证书签名，才能安装
            if (signingConfig == null) signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(project(":tests:device:acp-channel:common"))
}
