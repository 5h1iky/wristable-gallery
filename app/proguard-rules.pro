# 万能图库混淆规则（release minify=true）
# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
# ZXing
-keep class com.google.zxing.** { *; }
