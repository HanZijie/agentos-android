// tests/device/acp-channel/inapp —— W6 设备用例的 in-app 执行器。只以 debugImplementation 注入 :app 的 debug 包，
// 运行在 AgentOS 自己的 UID、独立的 :acptest 进程里，所以能以“本 App”的身份打开 ACP 通道、绑定 IAgentControl、
// 启动不导出的 AgentService，也能单独杀掉 :agent 或自己。release 包里没有它。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.agentos.test.acp.inapp"
    defaultConfig {
        // AgentOS 的 releaseTest 包（R8）带着本库：反射调用 IAgentControl 的部分要 keep
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(project(":tests:device:acp-channel:common"))
}
