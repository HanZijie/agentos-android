// tests/device/acp-channel/common —— ACP 通道设备测试的公共部分：场景执行框架、ACP 连接封装、测量工具、测试探针 AIDL。
// 被 :agent、:client 两个测试 App 和注入 AgentOS debug 包的 :inapp 共用。只用于测试，不进 zip。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.agentos.test.acp.common"
    buildFeatures {
        aidl = true
    }
}

dependencies {
    api(project(":sdk:acp-android"))
}
