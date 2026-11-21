# Keep JNI entry points matching liblocalapi.so symbols in android/src/lib.rs
-keep class dev.localapi.deepseek.Rust {
    native <methods>;
}

# Keep WebView @JavascriptInterface callbacks (KaTeX bitmap & live bridges)
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class dev.localapi.deepseek.ui.KatexBitmapRenderer$BmpBridge { *; }
-keep class dev.localapi.deepseek.ui.KatexBridgeHost { *; }

# Keep Android manifest components
-keep public class dev.localapi.deepseek.MainActivity
-keep public class dev.localapi.deepseek.ServerService
-keep public class dev.localapi.deepseek.UpdateJobService
