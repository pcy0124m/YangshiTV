# 保持原生 WebView 相关类的可访问性
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public *;
}
-keepclassmembers class * extends android.webkit.WebViewClient {
    public *;
}
-keepattributes JavascriptInterface
-keepclassmembers class com.hutou.yangshitv.** {
    @android.webkit.JavascriptInterface <methods>;
}
