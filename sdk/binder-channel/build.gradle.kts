// sdk:binder-channel —— binder-channel-v1：ACP 与 MCP 共用的 Binder 消息通道（IChannel AIDL、BinderChannel）。
// 协议见 docs/architecture.md 5.2，W5 冻结为 core/protocol/binder-channel-v1.md。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.agentos.channel"
    buildFeatures {
        aidl = true
    }
}

dependencies {
    testImplementation(libs.junit4)
}
