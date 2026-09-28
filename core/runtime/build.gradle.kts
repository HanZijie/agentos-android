// core:runtime —— :agent 运行时的 Kotlin 宿主层与 Pi 适配层（纯 Kotlin/JVM，不依赖 Android）。
// 包结构见 docs/implementation-plan.md 第 1 节：acp/ store/ scheduler/ router/ broker/ hooks/ skills/ ports/ memory/
// 由 A lane 负责；pi/ 和 net/ 由 B lane 负责（W3）。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // lane B（W3）：testFixtures 放 JsEngine 的电脑实现（quickjs-kt-jvm）和假模型端点，
    // 供本模块测试和电脑上的 ACP 一致性测试（W4）使用；主代码不依赖 quickjs-kt。
    `java-test-fixtures`
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

    // lane B（W3）：JsEngine 的电脑实现与假模型端点（src/testFixtures）
    testFixturesImplementation(libs.quickjs.kt)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.serialization.json)
}

// lane B（W3）：Pi 打包产物的契约测试（org.agentos.runtime.pi.PiBundleContractTest）读取
// core/pi-runtime/build.mjs 生成的 app/src/main/assets/pi-agent.js；没有生成时这些用例跳过。
tasks.named<Test>("test") {
    val piAssets = rootProject.layout.projectDirectory.dir("app/src/main/assets")
    systemProperty("agentos.piAssetsDir", piAssets.asFile.absolutePath)
    inputs.files(piAssets.file("pi-agent.js"), piAssets.file("model-catalog.json"))
        .optional()
        .withPropertyName("piBundle")
}
