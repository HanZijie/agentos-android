-keep class org.agentos.channel.IAcpService { *; }
-keep class org.agentos.channel.IAcpService$* { *; }
# ACP SDK 0.30.1 依赖的 kotlin-logging 7.0.0 带有 slf4j 桥接，引用了 org.slf4j.*，但 slf4j-api 只是它的
# compileOnly 依赖，不在 APK 里，R8 会报 Missing class。运行时 AcpAndroid 已切到 android.util.Log，
# 不会走到这些类（S3 第二部分，问题 2）。
-dontwarn org.slf4j.**
