// sdk:acp-android —— 给后装 App 用的 ACP 客户端 SDK：IAcpService、BinderAcpTransport、JsonRpcCodec、AcpAndroid（W5），
// AgentOs 入口（W24）。客户端、Agent 端（:agent）和 AgentOS 自带界面共用同一个 BinderAcpTransport。依赖固定为 acp:0.30.1。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.agentos.acp"
    defaultConfig {
        // 带给依赖方：IAcpService 的 keep 规则，以及 ACP SDK 需要的 -dontwarn org.slf4j.**（S3 问题 2）
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures {
        aidl = true
    }
    aidlPackagedList += "org/agentos/channel/IAcpService.aidl"
}

dependencies {
    api(project(":sdk:binder-channel"))
    api(libs.acp)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
