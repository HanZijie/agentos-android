// tests/device/mcp-plugin/plugin —— 测试用的插件 App（C7）：内嵌 Agent Plugin，导出 Binder MCP 服务（sdk:plugin-sdk）。
// 用途：S5 的 R8 验证（release 开 R8，自测入口在另一个进程里经 McpBinderClient 走完 initialize / tools/list / tools/call），
// 以及 C7b 里 Extension Host 的设备回归（发现、权限、取消、list_changed、进程死亡、空闲回收）。只用于测试，不进 zip。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.agentos.test.mcp.plugin"
    defaultConfig {
        applicationId = "org.agentos.test.mcp.plugin"
        versionCode = 1
        versionName = "1.0"
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
    implementation(project(":sdk:plugin-sdk"))
}
