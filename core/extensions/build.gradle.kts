// core:extensions —— 扩展的纯逻辑（Kotlin/JVM）：清单解析、工具命名、插件包校验、Hook 匹配与决定合并。
// 由 :app 的 :ext 进程（Extension Host）加载；设计见 docs/extensions.md。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // 注册表与审批存储（registry/）要用 core:runtime 的 ApprovalPolicy、ApprovalPolicyPort；它们都不依赖 Android。
    // 反方向没有依赖：core:runtime 不知道 core:extensions
    api(project(":core:runtime"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit)
    // 记忆丢失的测试用真实的 CapabilityBroker + FakeHostPort 看“模型能看到哪些工具”
    testImplementation(testFixtures(project(":core:runtime")))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Agent Plugins 1.0 的 schema 固定副本（core/protocol/agent-plugins-1.0/）随模块打包，运行时不联网拉取。
// 资源路径：org/agentos/extensions/agent-plugins-1.0/{plugin,mcp}.schema.json。ManifestReaderSchemaTest 用它核对手写的校验规则。
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.dir("core/protocol/agent-plugins-1.0")) {
        into("org/agentos/extensions/agent-plugins-1.0")
    }
}

// A12：SamplePluginsConformanceTest 读仓库根的 plugins/samples/*（App 合入 main 之前用 AGENTOS_SAMPLES_ROOT 指到各自的 worktree，
// 路径用 : 分隔）和 docs/sample-apps.md。它们（以及环境变量）要算进测试任务的输入，否则文件变了 Gradle 还会拿缓存的结果。
tasks.named<Test>("test") {
    val samplesRoot = providers.environmentVariable("AGENTOS_SAMPLES_ROOT").orElse("")
    inputs.property("agentosSamplesRoot", samplesRoot)
    val sampleFiles = listOf("assets/agent-plugin/**", "AndroidManifest.xml", "java/**/*.kt", "kotlin/**/*.kt")
    val roots = listOf(rootProject.layout.projectDirectory.asFile) + samplesRoot.get().split(File.pathSeparatorChar).filter { it.isNotBlank() }.map { File(it) }
    for (root in roots) {
        inputs.files(fileTree(File(root, "plugins/samples")) { include(sampleFiles.map { "*/src/main/$it" }); exclude("**/build/**") })
            .withPropertyName("sampleApps-${root.name}").withPathSensitivity(PathSensitivity.ABSOLUTE).optional()
    }
    inputs.file(rootProject.layout.projectDirectory.file("docs/sample-apps.md")).withPropertyName("sampleAppsDoc").optional()
    // ToolNamingGoldenTest：与设备验收的 Python 假模型共用的工具名样本
    inputs.file(rootProject.layout.projectDirectory.file("tests/device/acp-channel/tool_naming_golden.json")).withPropertyName("toolNamingGolden").optional()
}
