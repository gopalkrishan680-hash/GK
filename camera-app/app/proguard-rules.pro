# ML Kit / Google Play services discover their components by reflection.
# Without these rules R8 strips them and SubjectSegmentation.getClient() crashes at launch.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.odml.** { *; }
-keep class com.google.android.gms.common.moduleinstall.** { *; }
-keep class com.google.firebase.components.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
