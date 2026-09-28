// S3 第二部分：ACP over Binder 的一次性实验工程，不参与主构建。
// 版本按 docs/implementation-plan.md 第 2 节锁定：AGP 8.10.1、Kotlin 2.2.20、JDK 21、ACP SDK 0.30.1。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "s3-acp-over-binder"

include(":channel")   // binder-channel-v1 草案：IChannel + BinderChannel（W5 时迁到 sdk/binder-channel/）
include(":acp")       // IAcpService + BinderAcpTransport（W5 时迁到 sdk/acp-android/）
include(":testapi")   // 只给 spike 用的探针与压测 AIDL
include(":server")    // 模拟 AgentOS App：SDK 的 Agent 端跑在 :agent 进程
include(":client")    // 模拟第三方 App：SDK 的 Client + BinderAcpTransport，场景执行器
