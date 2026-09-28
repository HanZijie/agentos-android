-keep class org.agentos.channel.IAcpService { *; }
-keep class org.agentos.channel.IAcpService$* { *; }
# kotlin-logging 在 JVM 上走 slf4j；Android 上没有 slf4j 实现，SDK 的日志会被丢弃（NOP）。
-dontwarn org.slf4j.**
