// core:extensions —— 扩展的纯逻辑（Kotlin/JVM）：清单解析、工具命名、插件包校验、Hook 匹配与决定合并。
// 由 :app 的 :ext 进程（Extension Host）加载；设计见 docs/extensions.md。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit4)
}
