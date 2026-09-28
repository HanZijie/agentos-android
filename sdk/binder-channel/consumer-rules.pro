# binder-channel-v1：AIDL 生成的 Stub / Proxy 按接口描述符和事务号通信，R8 不能改名或删除，否则跨 App 不兼容。
-keep class org.agentos.channel.IChannel { *; }
-keep class org.agentos.channel.IChannel$* { *; }
