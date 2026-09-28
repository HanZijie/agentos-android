# AgentOS App 的 R8 规则。
# ACP Kotlin SDK 0.30.1 在 R8 release 下的行为由 S3 第二部分验证，需要的 keep 规则在那之后补充。

# lane B（W6/B3）：quickjs-kt 的 JNI 按名字回调 Kotlin 侧的类（绑定、类型转换、Promise 任务）。
# AAR 自带的 consumer 规则只 keep 了 QuickJs、QuickJsException 和几个绑定接口；S8 的 R8 release
# 验证（20/20）用的是下面这条整包规则。
-keep class com.dokar.quickjs.** { *; }
