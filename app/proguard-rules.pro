# Keep watch face, tile, complication and listener services referenced from the manifest
-keep class com.harshvardhan.ultrawatch.** extends android.app.Service { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
