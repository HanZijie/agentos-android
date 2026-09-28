pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "agentos-android"

// 目录与模块的对应见 docs/implementation-plan.md 第 1 节。
// core/pi-runtime 是 Node 工程，不是 Gradle 模块；由 :app 的 bundlePiAgent 任务调用它的 build.mjs。
include(
    ":core:runtime",
    ":core:extensions",
    ":sdk:binder-channel",
    ":sdk:acp-android",
    ":sdk:plugin-sdk",
    ":app",
    ":runner",
)

// 内嵌插件的示例 App（W17）：plugins/samples/<name>/ 下有 build.gradle.kts 就自动成为 :plugins:samples:<name>，
// 加示例时不用改这个文件。
rootDir.resolve("plugins/samples")
    .listFiles { file -> file.isDirectory && file.resolve("build.gradle.kts").isFile }
    ?.sortedBy { it.name }
    ?.forEach { include(":plugins:samples:${it.name}") }

// ACP 通道的设备测试（W5 回归、W6 用例）：adb 驱动，只用于测试，不进 zip。说明见 tests/device/acp-channel/README.md。
include(
    ":tests:device:acp-channel:common",
    ":tests:device:acp-channel:agent",
    ":tests:device:acp-channel:client",
)
