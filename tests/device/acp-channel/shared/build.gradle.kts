// tests/device/acp-channel/shared —— 第三方接入的“共享 UID”用例（docs/third-party-acp.md 4.1：调用方 UID 对应多个包一律 not_open）。
// 源码复用 :client（同一套 ThirdPartyScenarios），只是清单里声明了 sharedUserId，包名也不同。同一个 UID 里的第二个包由 third_party.py
// 用 aapt2 现场生成（只有清单的 APK，同样的 sharedUserId，同一把调试证书），所以不需要第二个模块。只用于测试，不进 zip。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.agentos.test.acp.shared"
    defaultConfig {
        applicationId = "org.agentos.test.acp.shared.a"
    }
    sourceSets["main"].java.srcDir("../client/src/main/java")
    // sharedUserId 从 API 29 起被弃用，但安装仍然支持；这里就是要测它
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":tests:device:acp-channel:common"))
}
