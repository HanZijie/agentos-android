// :runner —— Runner APK（org.agentos.runner），独立 UID，不申请任何权限。
// 用 /system/bin/sh 执行 Hooks、shell 工具和 Skill 脚本；Service 要求 RUN_COMMANDS 并校验调用方 UID（W21）。
// 与 AgentOS App 用同一张项目发布证书，由根 build.gradle.kts 统一配置。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.agentos.runner"
    defaultConfig {
        applicationId = "org.agentos.runner"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        aidl = true
    }
}

dependencies {
    testImplementation(libs.junit4)
}
