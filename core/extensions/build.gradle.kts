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
}

// Agent Plugins 1.0 的 schema 固定副本（core/protocol/agent-plugins-1.0/）随模块打包，运行时不联网拉取。
// 资源路径：org/agentos/extensions/agent-plugins-1.0/{plugin,mcp}.schema.json。ManifestReaderSchemaTest 用它核对手写的校验规则。
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.dir("core/protocol/agent-plugins-1.0")) {
        into("org/agentos/extensions/agent-plugins-1.0")
    }
}
