// sdk:plugin-sdk —— 给 App 开发者：在 App 里内嵌标准 Agent Plugin，用 Binder 提供 MCP 服务
// （IMcpService、McpBinderTransport、McpBinderService、McpBinderClient，W15；注解与模板，W26）。
// MCP 协议层是自己写的只做 tools 的 JSON-RPC（S5：官方 MCP Kotlin SDK 要求 kotlinx-serialization ≥ 1.9.0、
// kotlinx-io ≥ 0.8，与 ACP 0.30.1 共用的锁定版本冲突，见 docs/spikes/S5.md）；公开接口按 docs/sample-apps.md 第 3 节。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.agentos.plugin"
    defaultConfig {
        // 带给依赖方：IMcpService 的 keep 规则（R8）
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        aidl = true
    }
    // 把 IMcpService.aidl 打进 AAR，Extension Host（app）和第三方插件 App 都能 import
    aidlPackagedList += "org/agentos/channel/IMcpService.aidl"
}

dependencies {
    api(project(":sdk:binder-channel"))
    // 公开接口用 JsonObject / JsonElement（工具的 inputSchema、参数、结果）；只用 JSON 元素，不需要 serialization 插件
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
}
