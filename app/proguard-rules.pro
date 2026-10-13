# 保留 nodejs-mobile 的 JNI 入口类
-keep class com.janeasystems.rn_nodejs_mobile.** { *; }
-keep class nodejs.mobile.** { *; }
# 保留我们自己的 JS 桥方法（@JavascriptInterface 反射调用）
-keepclassmembers class com.stronghold.shell.AndroidBridge {
    public *;
}