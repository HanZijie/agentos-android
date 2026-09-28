package org.agentos.acp

/**
 * ACP SDK 在 Android 上的运行时前置条件。
 *
 * SDK 0.30.1 用 kotlin-logging 7.0.0 记日志。它的 Android 变体默认走 slf4j，而 slf4j-api 只是
 * compileOnly 依赖，不在 APK 里：不做处理时，SDK 第一次创建 logger 就抛
 * `NoClassDefFoundError: org/slf4j/LoggerFactory`（S3 第二部分在真机上发现，编译和 R8 都发现不了）。
 * 设置系统属性 `kotlin-logging-to-android-native=true` 后改用 android.util.Log。
 *
 * 必须在任何 SDK 类第一次记日志之前调用；BinderAcpTransport 的静态初始化里已经调用。
 */
object AcpAndroid {
    const val KOTLIN_LOGGING_ANDROID_NATIVE = "kotlin-logging-to-android-native"

    init {
        if (System.getProperty(KOTLIN_LOGGING_ANDROID_NATIVE) == null) {
            System.setProperty(KOTLIN_LOGGING_ANDROID_NATIVE, "true")
        }
    }

    /** 触发上面的 init；可以重复调用。 */
    fun ensureInitialized() = Unit
}
