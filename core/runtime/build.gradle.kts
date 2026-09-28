// core:runtime —— :agent 运行时的 Kotlin 宿主层与 Pi 适配层（纯 Kotlin/JVM，不依赖 Android）。
// 包结构见 docs/implementation-plan.md 第 1 节：acp/ store/ scheduler/ router/ broker/ hooks/ skills/ ports/ memory/
// 由 A lane 负责；pi/ 和 net/ 由 B lane 负责（W3）。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // ACP Agent 端（W4）。以 api 暴露：:app 的 :agent 进程直接用 SDK 的 Agent / Transport 类型接线
    api(libs.acp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // 给 Pi 用的 fetch（net/HostFetch.kt，W3）
    implementation(libs.okhttp)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
