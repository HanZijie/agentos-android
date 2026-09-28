// core:runtime —— :agent 运行时的 Kotlin 宿主层与 Pi 适配层（纯 Kotlin/JVM，不依赖 Android）。
// 包结构见 docs/implementation-plan.md 第 1 节：acp/ store/ scheduler/ router/ broker/ hooks/ skills/ ports/ memory/
// 由 A lane 负责；pi/ 和 net/ 由 B lane 负责（W3）。
//
// testFixtures（src/testFixtures/kotlin）：FakeAgentCore、FakeHostPort 等假实现，供本模块测试、
// W4 的 ACP 一致性测试和其他模块复用：testImplementation(testFixtures(project(":core:runtime")))。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

dependencies {
    // ACP Agent 端（W4）。以 api 暴露：:app 的 :agent 进程直接用 SDK 的 Agent / Transport 类型接线
    api(libs.acp)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    // Store 只依赖 SQLiteDriver 接口（HostPort.storage 提供驱动），Android 与电脑测试共用 schema
    api(libs.androidx.sqlite)
    // 给 Pi 用的 fetch（net/HostFetch.kt，W3）
    implementation(libs.okhttp)

    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesApi(libs.androidx.sqlite)
    testFixturesImplementation(libs.androidx.sqlite.bundled)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.sqlite.bundled)
}
