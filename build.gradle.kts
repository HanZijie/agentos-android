import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// ---- 工具链检查：版本锁定在 gradle/libs.versions.toml ----
val requiredJdk: String = libs.versions.jdk.get()
check(JavaVersion.current().majorVersion == requiredJdk) {
    "AgentOS 要求用 JDK $requiredJdk 运行 Gradle，当前是 ${JavaVersion.current()}" +
        "（${System.getProperty("java.home")}）。把 JAVA_HOME 指向 JDK $requiredJdk，见 README「开发」一节。"
}

val agentosVersion: String = providers.gradleProperty("agentos.version").get()
val agentosVersionCode: Int = providers.gradleProperty("agentos.versionCode").get().toInt()
val compileSdkLevel: Int = libs.versions.compileSdk.get().toInt()
val targetSdkLevel: Int = libs.versions.targetSdk.get().toInt()
val minSdkLevel: Int = libs.versions.minSdk.get().toInt()
val bytecodeTarget: String = libs.versions.jvmTarget.get()
val javaBytecodeTarget: JavaVersion = JavaVersion.toVersion(bytecodeTarget)

// ---- 项目发布证书：只从环境变量读取，私钥放在仓库外（生成方法见 README「开发」一节） ----
// 四个变量都没设时，release 构建不签名（产出 *-unsigned.apk）；只设了一部分视为配置错误。
val signingEnvNames = listOf(
    "AGENTOS_SIGNING_STORE_FILE",
    "AGENTOS_SIGNING_STORE_PASSWORD",
    "AGENTOS_SIGNING_KEY_ALIAS",
)
val signingEnv: Map<String, String?> = signingEnvNames.associateWith { providers.environmentVariable(it).orNull }
val signingKeyPassword: String? = providers.environmentVariable("AGENTOS_SIGNING_KEY_PASSWORD").orNull
val releaseSigningConfigured: Boolean = signingEnv.values.all { !it.isNullOrEmpty() }
check(releaseSigningConfigured || signingEnv.values.all { it.isNullOrEmpty() }) {
    "发布证书的环境变量只设了一部分，缺少：" + signingEnv.filterValues { it.isNullOrEmpty() }.keys.joinToString()
}
val releaseStoreFile: File? = signingEnv["AGENTOS_SIGNING_STORE_FILE"]?.takeIf { releaseSigningConfigured }?.let { file(it) }
if (releaseStoreFile != null) {
    check(releaseStoreFile.isFile) { "AGENTOS_SIGNING_STORE_FILE 指向的文件不存在" }
    val repoRoot = rootDir.canonicalFile
    check(!releaseStoreFile.canonicalFile.startsWith(repoRoot)) {
        "发布证书的私钥必须放在仓库外，不能放在 $repoRoot 里"
    }
}

allprojects {
    group = "org.agentos"
    version = agentosVersion
}

subprojects {
    // Kotlin：JVM 模块和 Android 模块统一字节码版本
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(bytecodeTarget))
        }
    }

    // 纯 Kotlin/JVM 模块（core:*）：不依赖 Android，在电脑上编译和测试
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = javaBytecodeTarget
            targetCompatibility = javaBytecodeTarget
        }
        tasks.withType<Test>().configureEach {
            testLogging {
                events("failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }

    // Android 库（sdk:*）
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<LibraryExtension> {
            compileSdk = compileSdkLevel
            defaultConfig {
                minSdk = minSdkLevel
            }
            compileOptions {
                sourceCompatibility = javaBytecodeTarget
                targetCompatibility = javaBytecodeTarget
            }
            testOptions.targetSdk = targetSdkLevel
            lint.targetSdk = targetSdkLevel
        }
    }

    // Android App（app、runner、plugins:samples:*）：AgentOS App 和 Runner 用同一张项目发布证书
    pluginManager.withPlugin("com.android.application") {
        extensions.configure<ApplicationExtension> {
            compileSdk = compileSdkLevel
            defaultConfig {
                minSdk = minSdkLevel
                targetSdk = targetSdkLevel
                versionCode = agentosVersionCode
                versionName = agentosVersion
            }
            compileOptions {
                sourceCompatibility = javaBytecodeTarget
                targetCompatibility = javaBytecodeTarget
            }
            if (releaseStoreFile != null) {
                val release = signingConfigs.create("agentosRelease") {
                    storeFile = releaseStoreFile
                    storePassword = signingEnv.getValue("AGENTOS_SIGNING_STORE_PASSWORD")
                    keyAlias = signingEnv.getValue("AGENTOS_SIGNING_KEY_ALIAS")
                    // PKCS12 的 key 密码与 store 密码相同；单独设了才用单独的
                    keyPassword = signingKeyPassword ?: signingEnv.getValue("AGENTOS_SIGNING_STORE_PASSWORD")
                    enableV1Signing = false
                    enableV2Signing = true
                    enableV3Signing = true
                }
                buildTypes.getByName("release").signingConfig = release
            }
        }
    }
}
