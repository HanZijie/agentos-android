// sdk:plugin-sdk —— 给 App 开发者：在 App 里内嵌标准 Agent Plugin，用 Binder 提供 MCP 服务
// （IMcpService、McpBinderTransport、McpBinderService、插件包校验与打包，W15；注解与模板，W26）。
// MCP Kotlin SDK 的版本在 S5 固定后再加依赖。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.agentos.plugin"
    buildFeatures {
        aidl = true
    }
}

dependencies {
    api(project(":sdk:binder-channel"))

    testImplementation(libs.junit4)
}
