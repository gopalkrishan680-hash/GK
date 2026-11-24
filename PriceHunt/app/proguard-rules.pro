# Keep JS interface / WebView related classes
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
