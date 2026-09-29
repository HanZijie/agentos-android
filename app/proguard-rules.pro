# AgentOS App 的 R8 规则。
# ACP Kotlin SDK 0.30.1 在 R8 release 下的行为由 S3 第二部分验证，需要的 keep 规则在那之后补充。

# lane B（W6/B3）：quickjs-kt 的 JNI 按名字回调 Kotlin 侧的类（绑定、类型转换、Promise 任务）。
# AAR 自带的 consumer 规则只 keep 了 QuickJs、QuickJsException 和几个绑定接口；S8 的 R8 release
# 验证（20/20）用的是下面这条整包规则。
-keep class com.dokar.quickjs.** { *; }

# lane C（W6/C4）：quickjs-kt 的原生库按名字访问 kotlin.UByteArray（HostFetch 的响应字节 ↔ JS Uint8Array）：
# 字段 storage:[B 和构造器 <init>([B)V（libquickjs.so 的 set_cls_ubyte_array / kt_ubyte_array_to_js_uint8array）。
# 没有这条时 R8 会改名或去掉它们，:agent 在第一次模型响应时 JNI abort（“fid == null in call to GetObjectField”，
# releaseTest 包在 API 35 模拟器上复现）。上面的 com.dokar.quickjs.** 规则管不到 kotlin 标准库的类。
-keep class kotlin.UByteArray {
    private final byte[] storage;
    <init>(byte[]);
}
