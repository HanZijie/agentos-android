# S3 spike：App 自己不加任何针对 ACP SDK 的规则。SDK 需要的规则（-dontwarn org.slf4j.**）由 :acp 的
# consumer-rules.pro 带进来，验证库自带的规则在 R8 下够用。
