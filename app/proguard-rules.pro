# EnerShip Travel — ProGuard rules

# Keep JavaScript interface methods (used by WebView bridge)
-keepclassmembers class com.enship.travel.MainActivity$AndroidBTInterface {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Bluetooth classes
-keep class android.bluetooth.** { *; }

# Don't warn about WebView/JavascriptInterface
-dontwarn android.webkit.JavascriptInterface
