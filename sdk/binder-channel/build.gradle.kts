// sdk:binder-channel —— binder-channel-v1：ACP 与 MCP 共用的 Binder 消息通道（IChannel AIDL、BinderChannel）。
// 规范见 core/protocol/binder-channel-v1.md（S3 定参的草案，真机复核后冻结）。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.agentos.channel"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures {
        aidl = true
    }
    // 把 IChannel.aidl 打进 AAR，依赖方（acp-android 的 IAcpService、plugin-sdk 的 IMcpService）才能 import
    aidlPackagedList += "org/agentos/channel/IChannel.aidl"
}

dependencies {
    // BinderChannel 的写协程、incoming 通道；版本与 acp 0.30.1 的传递依赖对齐
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
}
