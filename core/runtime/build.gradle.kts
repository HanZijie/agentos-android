// core:runtime —— :agent 运行时的 Kotlin 宿主层与 Pi 适配层（纯 Kotlin/JVM，不依赖 Android）。
// 包结构见 docs/implementation-plan.md 第 1 节：acp/ store/ scheduler/ router/ broker/ hooks/ skills/ ports/ memory/
// 由 A lane 负责；pi/ 和 net/ 由 B lane 负责（W3）。
//
// testFixtures（src/testFixtures/kotlin）：FakeAgentCore、FakeHostPort 等假实现，供本模块测试、
// W4 的 ACP 一致性测试和其他模块复用：testImplementation(testFixtures(project(":core:runtime")))。
// lane B（W3）：testFixtures 里还有 JsEngine 的电脑实现（pi/desktop，quickjs-kt-jvm）和假模型端点
// （pi/testing）；主代码不依赖 quickjs-kt。
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

    // lane B（W3）：JsEngine 的电脑实现与假模型端点（src/testFixtures）
    testFixturesImplementation(libs.quickjs.kt)
    testFixturesImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
}

// lane B（W3）：Pi 打包产物的契约测试（org.agentos.runtime.pi.PiBundleContractTest）读取
// core/pi-runtime/build.mjs 生成的 app/src/main/assets/pi-agent.js；没有生成时这些用例跳过。
// 同一次构建里也要打包 APK 时，先让 :app:bundlePiAgent 生成产物（只约束顺序，不会因此拉起 app 的打包）。
tasks.named<Test>("test") {
    val piAssets = rootProject.layout.projectDirectory.dir("app/src/main/assets")
    systemProperty("agentos.piAssetsDir", piAssets.asFile.absolutePath)
    inputs.files(piAssets.file("pi-agent.js"), piAssets.file("model-catalog.json"))
        .optional()
        .withPropertyName("piBundle")
    mustRunAfter(":app:bundlePiAgent")
}

// lane A（W4）：ACP SDK 0.30.1 依赖 kotlin-logging，它在 JVM 上默认走 slf4j，而 slf4j 不在类路径上，
// 第一次记日志就会 NoClassDefFoundError（S3 问题 2 的 JVM 版本）。电脑上的测试一律改走 java.util.logging。
tasks.withType<Test>().configureEach {
    systemProperty("kotlin-logging-to-jul", "true")
}

// lane A（W4）：tests/acp-conformance 启动电脑端 ACP Agent 进程（org.agentos.runtime.testing.AcpStdioAgent）要用的类路径。
// 输出 build/acp-conformance/classpath.txt；npm test 在没有这个文件时会先调用本任务。
val acpConformanceClasspath by tasks.registering {
    group = "agentos"
    description = "写出 ACP 一致性测试的电脑端 Agent 进程的类路径"
    val classpath = sourceSets["testFixtures"].runtimeClasspath
    val output = layout.buildDirectory.file("acp-conformance/classpath.txt")
    inputs.files(classpath).withPropertyName("classpath")
    outputs.file(output)
    doLast {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(classpath.files.joinToString(File.pathSeparator))
        }
    }
}
