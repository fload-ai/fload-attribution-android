# The SDK itself uses no reflection or serialization. These keep its public
# face under its own names for callers that look it up by name at runtime:
# .NET bindings and Unity's AndroidJavaClass go through JNI, so R8 cannot see
# them and would otherwise rename or remove these classes.
-keep class com.fload.attribution.Fload {
    public *;
}
-keep class com.fload.attribution.FloadOptions {
    public *;
}
-keep class com.fload.attribution.FloadWrapper {
    public *;
}
-keep enum com.fload.attribution.FloadLogLevel {
    *;
}
