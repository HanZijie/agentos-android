# quickjs-kt calls into Kotlin classes from JNI by name.
-keep class com.dokar.quickjs.** { *; }
-keep class org.agentos.spike.s8.** { *; }
-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
